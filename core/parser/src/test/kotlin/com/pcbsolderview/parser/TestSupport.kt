package com.pcbsolderview.parser

import com.pcbsolderview.parser.xml.XmlParserFactory
import org.kxml2.io.KXmlParser
import java.io.File

object Samples {
    val dir: File = File(System.getProperty("samples.dir") ?: "samples")
    fun file(path: String): File = File(dir, path).also { require(it.exists()) { "Missing fixture $it" } }
    fun input(path: String): InputFile = file(path).let { InputFile(it.name, it.readBytes()) }
    fun text(path: String): String = file(path).readText()
}

val testXml = XmlParserFactory { KXmlParser() }

/** mm -> µm */
fun mm(v: Double): Long = Math.round(v * 1000)

/** mil -> µm */
fun mil(v: Double): Long = Math.round(v * 25.4)
