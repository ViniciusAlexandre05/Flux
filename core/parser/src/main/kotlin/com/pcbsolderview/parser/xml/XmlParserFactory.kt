package com.pcbsolderview.parser.xml

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Supplies streaming XmlPullParser instances. On Android: `Xml.newPullParser()`; in JVM tests:
 * kXML2. Keeps the parser module free of Android dependencies.
 */
fun interface XmlParserFactory {
    fun newParser(): XmlPullParser

    fun create(input: InputStream, encoding: String? = null): XmlPullParser =
        newParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, encoding)
        }
}
