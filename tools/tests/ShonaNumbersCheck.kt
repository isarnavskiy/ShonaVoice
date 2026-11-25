// From the repository root:
// kotlinc app/src/main/java/dev/nikita/voice/{ShonaSpeechText,ShonaProsody}.kt tools/tests/ShonaNumbersCheck.kt -include-runtime -d /tmp/shona-numbers.jar
// java -jar /tmp/shona-numbers.jar
package dev.nikita.voice

fun main() {
    val cases = mapOf(
        "Kodhi 0004." to "Kodhi ziro ziro ziro ina.",
        "0123456789" to "ziro motsi piri tatu ina shanu tanhatu nomwe sere pfumbamwe",
        "+263-777" to "purasi piri tanhatu tatu chisungazwi nomwe nomwe nomwe",
        "- 5.20%" to "mainasi shanu chindaguma piri ziro muzana",
        "−7,42" to "mainasi nomwe chifemo ina piri",
        "0.01" to "ziro chindaguma ziro motsi",
        ".5" to "chindaguma shanu",
        "٦٤７" to "tanhatu ina nomwe",
        "Mhoro! Makadii?\nTinosangana." to "Mhoro! Makadii?\nTinosangana.",
        "Tine 5, toda 4!" to "Tine shanu, toda ina!",
        "4 – 9" to "ina chisungazwi pfumbamwe",
        "📞 ٢" to "📞 piri",
        "𝟏𝟎" to "motsi ziro",
        "\$05.99" to "dhora ziro shanu chindaguma pfumbamwe pfumbamwe"
    )
    cases.forEach {(before, after) -> check(ShonaSpeechText.normalize(before) == after) {
        "$before: ${ShonaSpeechText.normalize(before)}" } }
    val dates = listOf("25.11.2026", "25/11/2026")
    val dayFirst = "piri shanu, motsi motsi, piri ziro piri tanhatu"
    for (date in dates) {
        check(ShonaSpeechText.hasNumberGroups(date))
        check(ShonaSpeechText.normalize(date) == dayFirst)
    }
    check(ShonaSpeechText.normalize("2026-11-25") == "piri ziro piri tanhatu, motsi motsi, piri shanu")
    val time = "motsi ina, tatu ziro"
    check(ShonaSpeechText.normalize("14:30") == time)
    check(ShonaSpeechText.normalize("Nguva:14:30.") == "Nguva:$time.")
    for (value in listOf("Ndinosvika na14:30.", "Ndinosvika na 14:30.", "Ndinosvika na١٤:٣٠.")) {
        check(ShonaSpeechText.hasNumberGroups(value))
        check(ShonaSpeechText.normalize(value) == "Ndinosvika na $time.")
    }
    check(!ShonaSpeechText.hasNumberGroups("IDna14:30"))
    for (value in listOf("Musangano uri pa14:30.", "Musangano uri pa 14:30.")) {
        check(ShonaSpeechText.hasNumberGroups(value))
        check(ShonaSpeechText.normalize(value) == "Musangano uri pa $time.")
    }
    check(!ShonaSpeechText.hasNumberGroups("IDpa14:30"))
    check(ShonaSpeechText.normalize("٠٧:٠٩:٠٣") == "ziro nomwe, ziro pfumbamwe, ziro tatu")
    check(ShonaSpeechText.normalize("14:30. 25/11/2026.") == "$time. $dayFirst.")
    check(ShonaProsody.plan(ShonaSpeechText.normalize("14:30")) { it.isLetter() }.map { it.pauseAfterMs } == listOf(125, 0))
    check(ShonaProsody.plan(dayFirst) { it.isLetter() }.map { it.pauseAfterMs } == listOf(125, 125, 0))
    for (value in listOf("99:99", "14:90", "3/4", "3 / 4", "1.23", "31/02/2026", "1/2/2026/3", "1.2.2026.4")) {
        check(!ShonaSpeechText.hasNumberGroups(value)) { value }
    }
    for (value in listOf("3/4", "3 / 4", "99:99", "½", "10₽","50‰", "5²", "0 ≤ 1")) {
        check(runCatching { ShonaSpeechText.normalize(value) }.exceptionOrNull() is IllegalArgumentException) { value }
    }
    println("Shona numbers, date/time grouping and pause checks passed.")
}
