package example

import dev.nuaforma.template.Templates

fun main() {
    val result = Templates.compile("{customer | select(\"account\") | select(\"total\") | number(\"0.00\")}")
        .render(mapOf("customer" to mapOf("account" to mapOf("total" to 1.005))))
    check(result == "1.00")
    println("Independent Java-17 Kotlin value-pipe JAR passed")
}
