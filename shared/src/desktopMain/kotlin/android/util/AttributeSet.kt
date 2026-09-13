package android.util

import org.xmlpull.v1.XmlPullParser

/** AttributeSet mirror of the Android API (satisfied by XmlResourceParser). */
interface AttributeSet {
    fun getAttributeCount(): Int
    fun getAttributeName(index: Int): String
    fun getAttributeValue(index: Int): String
    fun getAttributeValue(name: String): String
    fun getAttributeValue(namespace: String?, name: String?): String?
    fun getAttributeBooleanValue(index: Int, defaultValue: Boolean): Boolean
    fun getAttributeBooleanValue(namespace: String?, name: String, defaultValue: Boolean): Boolean
    fun getAttributeIntValue(index: Int, defaultValue: Int): Int
    fun getAttributeIntValue(namespace: String?, name: String, defaultValue: Int): Int
    fun getAttributeResourceValue(index: Int, defaultValue: Int): Int
    fun getAttributeResourceValue(namespace: String?, name: String, defaultValue: Int): Int
    fun getIdAttribute(): String?
    fun getClassAttribute(): String?
    fun getAttributeNameResource(index: Int): Int
    fun getAttributeListValue(namespace: String?, attribute: String?, options: Array<String>?, defaultValue: Int): Int
}
