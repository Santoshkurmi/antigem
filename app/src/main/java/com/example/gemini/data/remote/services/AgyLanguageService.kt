package com.example.gemini.data.remote.services

import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.core.AgyOkHttpClient
import com.squareup.wire.GrpcClient
import exa.language_server_pb.GrpcLanguageServerServiceClient
import exa.language_server_pb.LanguageServerServiceClient

/**
 * Direct typed entrypoint for Antigravity LanguageServerService gRPC APIs.
 * Automatically connects to the active Hub URL using the shared OkHttpClient connection pool.
 */
val AgyLanguageService: LanguageServerServiceClient
    get() = GrpcLanguageServerServiceClient(
        GrpcClient.Builder()
            .client(AgyOkHttpClient.client)
            .baseUrl(AuthPreferences.currentHubUrl.trimEnd('/'))
            .build()
    )
