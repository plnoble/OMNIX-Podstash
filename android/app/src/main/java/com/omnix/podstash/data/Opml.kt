package com.omnix.podstash.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object Opml {
    fun parse(xml: String): List<Show> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val p = factory.newPullParser()
        p.setInput(StringReader(xml))
        val out = mutableListOf<Show>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name.equals("outline", true)) {
                val feed = (p.getAttributeValue(null, "xmlUrl")
                    ?: p.getAttributeValue(null, "xmlurl")
                    ?: "").trim()
                val name = (p.getAttributeValue(null, "text")
                    ?: p.getAttributeValue(null, "title")
                    ?: "").trim()
                if (feed.startsWith("http")) {
                    out += Show(
                        id = "",
                        name = name.ifBlank { feed },
                        feedUrl = feed,
                        subscribed = true,
                    )
                }
            }
            event = p.next()
        }
        return out.distinctBy { it.feedUrl }
    }

    fun write(shows: List<Show>): String {
        val body = shows.filter { it.feedUrl.isNotBlank() }.joinToString("\n") { s ->
            val text = escape(s.name.ifBlank { s.feedUrl })
            val url = escape(s.feedUrl)
            """    <outline type="rss" text="$text" title="$text" xmlUrl="$url"/>"""
        }
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<opml version="2.0">
            |  <head>
            |    <title>OMNIX-Podstash</title>
            |  </head>
            |  <body>
            |$body
            |  </body>
            |</opml>
            |""".trimMargin()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace("\"", "&quot;")
}
