package androidx.appcompat.app

import android.app.Activity
import android.os.Bundle
import androidx.fragment.app.FragmentManager

/**
 * androidx.appcompat stubs. DesktopContext is an AppCompatActivity so plugin
 * casts (context as AppCompatActivity) succeed. The support fragment manager
 * is functional: show(fragmentManager, tag) executes the fragment lifecycle
 * and records its view for the shadow renderer.
 */
open class AppCompatActivity : Activity() {
    open fun onCreate(savedInstanceState: Bundle?) {}
    fun setContentView(layoutResId: Int) {}
    fun getSupportFragmentManager(): FragmentManager = FragmentManager()
    fun getSupportActionBar(): Any? = null
    fun setSupportActionBar(toolbar: Any?) {}
}
