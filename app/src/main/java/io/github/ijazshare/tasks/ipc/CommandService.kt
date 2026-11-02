/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.ipc

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder

/** The packages that may drive Tasks (4.2.3); the signature check still applies to each. */
val ALLOWED_CALLERS = setOf(
    "io.github.ijazshare.assistant",
    "io.github.ijazshare.assistant.debug",
    "io.github.ijazshare.tasks.connector",
    "io.github.ijazshare.tasks.connector.debug",
)

/** The calling package if it is the only one in its uid, is allowed and has our signature; else null. */
fun trustedCaller(packages: Array<String>?, signatureMatches: (String) -> Boolean): String? =
    packages?.singleOrNull()?.takeIf { it in ALLOWED_CALLERS && signatureMatches(it) }

/** Spec 4.1: an exported bound service behind a signature permission, with the caller checked again on every call. */
class CommandService : Service() {
    private val commands by lazy { Commands(applicationContext) }

    override fun onBind(intent: Intent?): IBinder = object : ITaskCommand.Stub() {
        override fun contractVersion() = CONTRACT_VERSION

        override fun handle(requestJson: String?): String {
            val json = requestJson.orEmpty()
            val caller = trustedCaller(packageManager.getPackagesForUid(Binder.getCallingUid())) {
                packageManager.checkSignatures(packageName, it) == PackageManager.SIGNATURE_MATCH
            }
            return if (caller == null) {
                errorResponse(Wire.parse(json)?.requestId, "caller_not_trusted")
            } else {
                commands.handle(json, caller)
            }
        }
    }
}
