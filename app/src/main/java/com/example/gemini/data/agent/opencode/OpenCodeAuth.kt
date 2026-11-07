package com.example.gemini.data.agent.opencode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How OpenCode's providers sign in (`GET /integration`): API key, OAuth (device code, or a browser that comes back to
 * 127.0.0.1 on the machine OpenCode runs on), "external" (credentials already on that machine: Claude Code's login,
 * an AWS profile, gcloud), and environment variables. Some methods first ask a few questions (a form).
 */
object OpenCodeAuth {

    /** One question of a sign-in form (GitHub.com or Enterprise, GitLab URL, Snowflake account…). */
    data class Field(
        val key: String,
        val type: String,
        val title: String,
        val description: String?,
        val placeholder: String?,
        val required: Boolean,
        val hidden: Boolean,
        val default: String?,
        /** value → label (and description) for a choice. */
        val options: List<Triple<String, String, String?>>,
        /** A choice field also takes a typed answer. */
        val custom: Boolean,
        /** Shown only when these hold: key, "eq"/"neq", value. */
        val conditions: List<Triple<String, String, String>>,
        val pattern: String?
    )

    data class Method(
        val type: String,
        val id: String?,
        val label: String?,
        val envNames: List<String>,
        val form: List<Field>
    ) {
        val isKey get() = type == "key"
        val isOAuth get() = type == "oauth"
        val isExternal get() = type == "external"
        /** The method shows a code to enter on the provider's site (works from any device). */
        val looksLikeDeviceCode get() = isOAuth && (id == "device" || id?.contains("headless") == true || label?.contains("device", true) == true)
    }

    data class Connection(val id: String, val label: String, val method: String?)

    data class Integration(val id: String, val name: String, val methods: List<Method>, val connections: List<Connection>) {
        val signInMethods get() = methods.filter { it.isKey || it.isOAuth || it.isExternal }
        val connected get() = connections.isNotEmpty()
    }

    data class Attempt(
        val id: String,
        val url: String,
        val instructions: String,
        /** "auto": finishes by itself; "code": the user pastes a code from the provider. */
        val mode: String,
        val paste: Boolean,
        val expiresAt: Long?
    ) {
        /** The code to enter on the provider's page ("Enter code: 43D3-E418"), if the attempt shows one. */
        val userCode: String? get() = codeIn(instructions) ?: queryParam(url, "user_code")

        /**
         * The browser is sent back to 127.0.0.1 / localhost on the machine OpenCode runs on. On the phone that works;
         * with the server elsewhere the browser's address has to be handed back (see the bridge's oauth-callback).
         */
        val returnsToLoopback: Boolean get() = queryParam(url, "redirect_uri")?.let { isLoopback(it) } == true
    }

    sealed class AttemptStatus {
        object Pending : AttemptStatus()
        object Complete : AttemptStatus()
        data class Failed(val message: String) : AttemptStatus()
    }

    data class Credential(val id: String, val integrationId: String, val label: String, val active: Boolean, val type: String?)

    // ------------------------------------------------------------------ parsing

    fun integrations(data: JsonArray?): List<Integration> = data.orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        Integration(
            id = id,
            name = o.str("name") ?: id,
            methods = o.a("methods").orEmpty().mapNotNull { it.obj() }.map(::method),
            connections = o.a("connections").orEmpty().mapNotNull { it.obj() }.mapNotNull { c ->
                Connection(c.str("id") ?: return@mapNotNull null, c.str("label") ?: c.str("method") ?: c.str("type") ?: "Account", c.str("method"))
            }
        )
    }.sortedBy { it.name.lowercase() }

    fun method(o: JsonObject) = Method(
        type = o.str("type").orEmpty(),
        id = o.str("id"),
        label = o.str("label"),
        envNames = o.a("names").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        form = o.a("form").orEmpty().mapNotNull { it.obj() }.mapNotNull(::field)
    )

    fun field(o: JsonObject): Field? {
        val key = o.str("key") ?: return null
        return Field(
            key = key,
            type = o.str("type") ?: "string",
            title = o.str("title") ?: key,
            description = o.str("description"),
            placeholder = o.str("placeholder"),
            required = o.bool("required") == true,
            hidden = o.bool("hidden") == true,
            default = (o["default"] as? JsonPrimitive)?.contentOrNull,
            options = o.a("options").orEmpty().mapNotNull { it.obj() }.mapNotNull { op ->
                val v = op.str("value") ?: return@mapNotNull null
                Triple(v, op.str("label") ?: v, op.str("description"))
            },
            custom = o.bool("custom") == true,
            conditions = o.a("when").orEmpty().mapNotNull { it.obj() }.mapNotNull { w ->
                Triple(w.str("key") ?: return@mapNotNull null, w.str("op") ?: "eq", (w["value"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            },
            pattern = o.str("pattern")
        )
    }

    fun attempt(o: JsonObject?): Attempt? {
        val d = o.o("data") ?: o ?: return null
        val id = d.str("attemptID") ?: return null
        return Attempt(
            id = id,
            url = d.str("url").orEmpty(),
            instructions = d.str("instructions").orEmpty(),
            mode = d.str("mode") ?: "auto",
            paste = d.bool("paste") == true,
            expiresAt = d.o("time").long("expires")
        )
    }

    fun status(o: JsonObject?): AttemptStatus {
        val d = o.o("data") ?: o
        return when (d.str("status")) {
            "complete" -> AttemptStatus.Complete
            "failed" -> AttemptStatus.Failed(d.str("message") ?: "The sign-in failed.")
            else -> AttemptStatus.Pending
        }
    }

    fun credentials(data: JsonArray?): List<Credential> = data.orEmpty().mapNotNull { it.obj() }.mapNotNull { c ->
        Credential(
            id = c.str("id") ?: return@mapNotNull null,
            integrationId = c.str("integrationID").orEmpty(),
            label = c.str("label") ?: "Account",
            active = c.bool("active") == true,
            type = c.str("type")
        )
    }

    // ------------------------------------------------------------------ forms

    /** Fields to show for [answers] so far (hidden fields and unmet conditions are left out). */
    fun visibleFields(form: List<Field>, answers: Map<String, String>): List<Field> = form.filter { f ->
        !f.hidden && f.conditions.all { (k, op, v) ->
            val cur = answers[k] ?: form.firstOrNull { it.key == k }?.default.orEmpty()
            if (op == "neq") cur != v else cur == v
        }
    }

    /** The answer object for the start call: visible answers plus the defaults of hidden fields. */
    fun answer(form: List<Field>, answers: Map<String, String>): Map<String, JsonElement> {
        val shown = visibleFields(form, answers).map { it.key }.toSet()
        val out = LinkedHashMap<String, JsonElement>()
        for (f in form) {
            val v = when {
                f.key in shown -> answers[f.key]?.trim()?.takeIf { it.isNotEmpty() } ?: f.default
                f.hidden -> f.default
                else -> null
            } ?: continue
            out[f.key] = when (f.type) {
                "number" -> v.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(v)
                "integer" -> v.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(v)
                "boolean" -> JsonPrimitive(v == "true")
                else -> JsonPrimitive(v)
            }
        }
        return out
    }

    /** What is still missing before the form can be sent (null = ready). */
    fun missing(form: List<Field>, answers: Map<String, String>): String? =
        visibleFields(form, answers).firstOrNull { f ->
            val v = answers[f.key]?.trim().orEmpty().ifEmpty { f.default.orEmpty() }
            (f.required && v.isEmpty()) || (v.isNotEmpty() && f.pattern != null && runCatching { !Regex(f.pattern).containsMatchIn(v) }.getOrDefault(false))
        }?.title

    // ------------------------------------------------------------------ helpers

    private val CODE = Regex("""code[:\s]+([A-Z0-9]{3,}(?:-[A-Z0-9]{3,})*)""", RegexOption.IGNORE_CASE)

    fun codeIn(text: String): String? = CODE.find(text)?.groupValues?.get(1)?.takeIf { it.any(Char::isDigit) || it.contains('-') }

    fun queryParam(url: String, name: String): String? = runCatching {
        val q = java.net.URI(url).rawQuery ?: return null
        q.split('&').firstNotNullOfOrNull { kv ->
            val (k, v) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k == name) java.net.URLDecoder.decode(v, "UTF-8") else null
        }
    }.getOrNull()

    fun isLoopback(url: String): Boolean = runCatching {
        val h = java.net.URI(url).host?.lowercase()
        h == "127.0.0.1" || h == "localhost" || h == "::1" || h == "[::1]"
    }.getOrDefault(false)

    /** A label for a method in the provider's list. */
    fun methodTitle(m: Method): String = when {
        m.isKey -> m.label ?: "API key"
        m.isExternal -> m.label ?: "Use the server's login"
        else -> m.label ?: "Sign in"
    }
}
