package android.view.animation

/** Interpolator stubs — animations are recorded as no-ops. */
class DecelerateInterpolator : android.animation.TimeInterpolator {
    constructor()
    constructor(factor: Float)

    override fun getInterpolation(input: Float): Float = input
}

class AccelerateDecelerateInterpolator : android.animation.TimeInterpolator {
    override fun getInterpolation(input: Float): Float = input
}

class AccelerateInterpolator : android.animation.TimeInterpolator {
    constructor()
    constructor(factor: Float)

    override fun getInterpolation(input: Float): Float = input
}

class LinearInterpolator : android.animation.TimeInterpolator {
    override fun getInterpolation(input: Float): Float = input
}

class OvershootInterpolator : android.animation.TimeInterpolator {
    constructor()
    constructor(tension: Float)

    override fun getInterpolation(input: Float): Float = input
}

class BounceInterpolator : android.animation.TimeInterpolator {
    override fun getInterpolation(input: Float): Float = input
}

class AnticipateInterpolator : android.animation.TimeInterpolator {
    constructor()
    constructor(tension: Float)

    override fun getInterpolation(input: Float): Float = input
}

class CycleInterpolator(cycles: Float) : android.animation.TimeInterpolator {
    override fun getInterpolation(input: Float): Float = input
}
