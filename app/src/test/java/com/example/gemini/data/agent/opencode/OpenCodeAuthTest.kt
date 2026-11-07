package com.example.gemini.data.agent.opencode

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of OpenCode's sign-in API, with the shapes OpenCode 2.2.6 returned. */
class OpenCodeAuthTest {
    private fun arr(s: String) = OpenCodeJson.parseToJsonElement(s).jsonArray
    private fun obj(s: String) = OpenCodeJson.parseToJsonElement(s).jsonObject

    private val integrations = OpenCodeAuth.integrations(arr("""[
      {"id":"github-copilot","name":"GitHub Copilot","methods":[{"type":"env","names":["GITHUB_TOKEN"]},
        {"id":"device","type":"oauth","label":"Login with GitHub Copilot","form":[
          {"key":"deploymentType","title":"Select GitHub deployment type","required":true,"type":"string","options":[{"value":"github.com","label":"GitHub.com"},{"value":"enterprise","label":"GitHub Enterprise"}]},
          {"key":"enterpriseUrl","title":"Enter your GitHub Enterprise URL or domain","required":true,"when":[{"key":"deploymentType","op":"eq","value":"enterprise"}],"type":"string"}]}],
       "connections":[]},
      {"id":"opencode","name":"OpenCode Console","methods":[{"type":"key","label":"API key (service account)"},
        {"id":"device","type":"oauth","label":"OpenCode Console account","form":[{"key":"server","hidden":true,"type":"string","format":"uri","default":"https://opencode.ai/console"}]}],
       "connections":[{"type":"credential","id":"cred_1","label":"work","method":"key"}]},
      {"id":"snowflake-cortex","name":"Snowflake Cortex","methods":[{"id":"browser","type":"oauth","label":"Login with Snowflake","form":[
          {"key":"account","title":"Snowflake account","required":true,"type":"string"},{"key":"role","title":"Snowflake role","type":"string"}]}],"connections":[]},
      {"id":"claude-agent","name":"Claude Agent","methods":[{"id":"claude-login","type":"external","label":"Claude Code login (claude auth login)"}],"connections":[]}
    ]"""))

    @Test
    fun integrationsAndMethods() {
        val copilot = integrations.first { it.id == "github-copilot" }
        assertEquals(1, copilot.signInMethods.size) // env is not a sign-in method
        assertTrue(copilot.signInMethods.single().looksLikeDeviceCode)
        val oc = integrations.first { it.id == "opencode" }
        assertTrue(oc.connected)
        assertEquals("work", oc.connections.single().label)
        assertTrue(integrations.first { it.id == "claude-agent" }.signInMethods.single().isExternal)
    }

    @Test
    fun formConditionsDefaultsAndRequiredFields() {
        val form = integrations.first { it.id == "github-copilot" }.signInMethods.single().form
        assertEquals(listOf("deploymentType"), OpenCodeAuth.visibleFields(form, emptyMap()).map { it.key })
        assertEquals("Select GitHub deployment type", OpenCodeAuth.missing(form, emptyMap()))
        val enterprise = mapOf("deploymentType" to "enterprise")
        assertEquals(listOf("deploymentType", "enterpriseUrl"), OpenCodeAuth.visibleFields(form, enterprise).map { it.key })
        assertEquals("Enter your GitHub Enterprise URL or domain", OpenCodeAuth.missing(form, enterprise))
        assertNull(OpenCodeAuth.missing(form, mapOf("deploymentType" to "github.com")))
        // a field hidden by its condition is not sent
        assertEquals(setOf("deploymentType"), OpenCodeAuth.answer(form, mapOf("deploymentType" to "github.com", "enterpriseUrl" to "x")).keys)

        val console = integrations.first { it.id == "opencode" }.signInMethods.first { it.isOAuth }.form
        assertTrue(OpenCodeAuth.visibleFields(console, emptyMap()).isEmpty())
        assertEquals(JsonPrimitive("https://opencode.ai/console"), OpenCodeAuth.answer(console, emptyMap())["server"])

        val snow = integrations.first { it.id == "snowflake-cortex" }.signInMethods.single().form
        assertEquals("Snowflake account", OpenCodeAuth.missing(snow, emptyMap()))
        assertNull(OpenCodeAuth.missing(snow, mapOf("account" to "myorg-acct")))
        assertEquals(setOf("account"), OpenCodeAuth.answer(snow, mapOf("account" to "myorg-acct")).keys)
    }

    @Test
    fun attemptsCodesAndLoopback() {
        val copilot = OpenCodeAuth.attempt(obj("""{"data":{"attemptID":"con_1","url":"https://github.com/login/device","instructions":"Enter code: 43D3-E418","mode":"auto","time":{"created":1,"expires":2}}}"""))!!
        assertEquals("43D3-E418", copilot.userCode)
        assertFalse(copilot.returnsToLoopback)
        assertEquals(2L, copilot.expiresAt)
        val xai = OpenCodeAuth.attempt(obj("""{"data":{"attemptID":"con_2","url":"https://accounts.x.ai/oauth2/device?user_code=WGM4-THK5","instructions":"Open https://accounts.x.ai/oauth2/device on any device and enter code: WGM4-THK5","mode":"auto","time":{"created":1,"expires":2}}}"""))!!
        assertEquals("WGM4-THK5", xai.userCode)
        val chatgpt = OpenCodeAuth.attempt(obj("""{"data":{"attemptID":"con_3","url":"https://auth.openai.com/api/accounts/authorize?client_id=x&response_type=code&redirect_uri=http%3A%2F%2F127.0.0.1%3A46821%2Fauth%2Fcallback&state=s","instructions":"Complete authorization in your browser. This window will close automatically.","mode":"auto","time":{"created":1,"expires":2}}}"""))!!
        assertNull(chatgpt.userCode)
        assertTrue(chatgpt.returnsToLoopback)

        assertTrue(OpenCodeAuth.status(obj("""{"data":{"status":"complete","time":{"created":1,"expires":2}}}""")) is OpenCodeAuth.AttemptStatus.Complete)
        val failed = OpenCodeAuth.status(obj("""{"data":{"status":"failed","message":"denied","time":{"created":1,"expires":2}}}"""))
        assertEquals("denied", (failed as OpenCodeAuth.AttemptStatus.Failed).message)
        assertTrue(OpenCodeAuth.status(obj("""{"data":{"status":"pending","time":{"created":1,"expires":2}}}""")) is OpenCodeAuth.AttemptStatus.Pending)
    }

    @Test
    fun rejectedAccountOffersToReconnect() {
        val t = OpenCodeTranscript("ses_a")
        t.apply("session.step.started", obj("""{"sessionID":"ses_a","assistantMessageID":"msg_a"}"""))
        t.apply("session.step.failed", obj("""{"sessionID":"ses_a","assistantMessageID":"msg_a","error":{"type":"provider.auth","message":"Invalid API key","status":401}}"""))
        assertTrue(t.toChatMessages("ses_a").single().content.contains("\"action\":\"opencode_providers\""))

        val other = OpenCodeTranscript("ses_b")
        other.apply("session.step.started", obj("""{"sessionID":"ses_b","assistantMessageID":"msg_b"}"""))
        other.apply("session.step.failed", obj("""{"sessionID":"ses_b","assistantMessageID":"msg_b","error":{"type":"provider.rate-limit","message":"slow down","status":429}}"""))
        assertFalse(other.toChatMessages("ses_b").single().content.contains("opencode_providers"))
    }
}
