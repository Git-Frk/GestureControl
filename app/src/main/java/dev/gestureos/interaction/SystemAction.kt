package dev.gestureos.interaction

enum class SystemAction {
    TAP, SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, SWIPE_DOWN, BACK, HOME;

    val isNavigation get() = this == BACK || this == HOME
    val isSwipe get() = this != TAP && !isNavigation
}
