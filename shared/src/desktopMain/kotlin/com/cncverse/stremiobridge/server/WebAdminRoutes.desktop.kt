package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.server.web.WebAdmin
import com.cncverse.stremiobridge.server.web.installWebAdminRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

/**
 * Desktop/JVM actual for the web admin hook: mounts the admin panel routes
 * onto the main addon server when the panel is enabled (headless server app
 * always, desktop with CNC_WEB_ADMIN=1).
 */
actual fun Application.setupWebAdminRoutes() {
    if (WebAdmin.isEnabled) {
        routing { installWebAdminRoutes() }
    }
}
