package com.demon.cmfassistant.service

import android.app.admin.DeviceAdminReceiver

/** Lets the assistant lock the screen. It asks for nothing else (no wipe, no password changes). */
class AdminReceiver : DeviceAdminReceiver()
