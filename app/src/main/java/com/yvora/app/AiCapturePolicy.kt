package com.yvora.app
/** Only capture an active AI screen if credential entry is inactive and the field is empty. */
internal object AiCapturePolicy {
    fun protect(foreground:Boolean,hasKey:Boolean,keyFocused:Boolean)=!foreground || hasKey || keyFocused
}
