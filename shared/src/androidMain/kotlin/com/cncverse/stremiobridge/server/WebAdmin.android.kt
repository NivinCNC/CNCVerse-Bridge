package com.cncverse.stremiobridge.server

import io.ktor.server.application.Application

/**
 * Android actual: the web admin panel is not mounted — Android ships the
 * native Compose UI with the same capabilities.
 */
actual fun Application.setupWebAdminRoutes() {
    // no-op
}
