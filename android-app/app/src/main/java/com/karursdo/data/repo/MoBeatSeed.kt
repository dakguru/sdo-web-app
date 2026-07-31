package com.karursdo.data.repo

/**
 * Mail Overseer beat offices transcribed from the “MAIL OVERSEER BEATS - VISIT
 * REGISTER 2026" (India Post, Karur Sub Division). Each entry is the office name,
 * its authoritative office-master id ([officeId], verified against the Karur Sub
 * Division office master), and its recorded visit dates (ISO yyyy-MM-dd, ascending).
 *
 * [officeId] is baked in (not fuzzy-matched at seed time) so every beat office links
 * to the correct office record. Seven register spellings differ from the master and
 * were resolved explicitly: Mokkanankurichi -> Mookanankurichi, Thoranakalpatti ->
 * Thoranakkalpatti, Panchamadevi -> Panjamadevi, Renganathanpettai ->
 * Renganathampettai, Kuppuchipalayam -> Kuppachipalayam, Nanjaypugalur ->
 * Nanjaipugalur, Punjaithottakuruchi -> Punjaithottakurichi.
 *
 * Notes on the source register:
 *  - Two visit rounds are recorded per office; a few MO-II offices instead carry a
 *    single completed visit plus a hand-written "next visit" date (kept here as the
 *    latest recorded date).
 *  - A couple of second-round dates were ambiguous in the handwriting and were read
 *    to the nearest quarter: Othaiyur 2 (2026-06-08) and Panchamadevi 2 (2026-06-19).
 */
object MoBeatSeed {

    const val MO_I = "MO_I"
    const val MO_II = "MO_II"

    data class SeedOffice(
        val serial: Int,
        val name: String,
        val officeId: String?,
        val visits: List<String>
    )

    // Mail Overseer I - 31 offices
    val moI: List<SeedOffice> = listOf(
        SeedOffice(1, "Kathalapatti", "29106813", listOf("2026-02-19", "2026-05-19")),
        SeedOffice(2, "Emur", "29106770", listOf("2026-01-17", "2026-04-15")),
        SeedOffice(3, "Koyampalli", "29106829", listOf("2026-03-10", "2026-06-03")),
        SeedOffice(4, "Melapalayam", "29106857", listOf("2026-02-09", "2026-05-11")),
        SeedOffice(5, "Puliyur", "29106933", listOf("2026-01-14", "2026-04-13")),
        SeedOffice(6, "Somur", "29106970", listOf("2026-02-26", "2026-05-27")),
        SeedOffice(7, "Uppidamangalam", "29106990", listOf("2026-03-05", "2026-06-05")),
        SeedOffice(8, "Vedichipalayam", "29107002", listOf("2026-03-20", "2026-06-29")),
        SeedOffice(9, "Veerarakkiam", "29107004", listOf("2026-02-16", "2026-05-14")),
        SeedOffice(10, "Karuppur", "29106811", listOf("2026-01-07", "2026-04-06")),
        SeedOffice(11, "Kattalai", "29106814", listOf("2026-01-30", "2026-04-29")),
        SeedOffice(12, "Manavasi", "29106848", listOf("2026-03-07", "2026-06-11")),
        SeedOffice(13, "Renganathapuram", "29106950", listOf("2026-02-13", "2026-05-12")),
        SeedOffice(14, "Valayalkaranpudur", "29106996", listOf("2026-02-27", "2026-05-25")),
        SeedOffice(15, "Manavadi", "29106847", listOf("2026-03-16", "2026-06-15")),
        SeedOffice(16, "Mokkanankurichi", "29106865", listOf("2026-01-19", "2026-04-18")),
        SeedOffice(17, "Paganatham", "29106898", listOf("2026-02-17", "2026-05-15")),
        SeedOffice(18, "Kodangipatti", "29106821", listOf("2026-01-13", "2026-04-10")),
        SeedOffice(19, "Appipalayam", "29106748", listOf("2026-03-02", "2026-05-29")),
        SeedOffice(20, "Kakkavadi", "29106788", listOf("2026-01-22", "2026-04-20")),
        SeedOffice(21, "Puthambur", "29106941", listOf("2026-02-05", "2026-05-02")),
        SeedOffice(22, "Sukkaliyur", "29106972", listOf("2026-03-14", "2026-06-13")),
        SeedOffice(23, "Thalapatti", "29106975", listOf("2026-03-26", "2026-06-30")),
        SeedOffice(24, "Thoranakalpatti", "29106984", listOf("2026-02-24", "2026-05-21")),
        SeedOffice(25, "Othaiyur", "29109278", listOf("2026-03-13", "2026-06-08")),
        SeedOffice(26, "Nerur", "29106893", listOf("2026-01-02", "2026-04-01")),
        SeedOffice(27, "Palaiyur", "29106899", listOf("2026-02-07", "2026-05-05")),
        SeedOffice(28, "Panchamadevi", "29106908", listOf("2026-03-18", "2026-06-19")),
        SeedOffice(29, "Renganathanpettai", "29106949", listOf("2026-01-28", "2026-04-24")),
        SeedOffice(30, "Senapiratti", "29106961", listOf("2026-01-27", "2026-04-22")),
        SeedOffice(31, "S.Vellalapatti", "29106952", listOf("2026-03-23", "2026-06-23"))
    )

    // Mail Overseer II - 29 offices
    val moII: List<SeedOffice> = listOf(
        SeedOffice(1, "Athur", "29106752", listOf("2026-03-05", "2026-05-13")),
        SeedOffice(2, "Poolampalayam", "29106925", listOf("2026-01-02", "2026-04-13")),
        SeedOffice(3, "Kuppuchipalayam", "29106834", listOf("2026-01-20", "2026-04-15")),
        SeedOffice(4, "Vangal East", "29106998", listOf("2026-03-13", "2026-05-18")),
        SeedOffice(5, "Minnampalli", "29106861", listOf("2026-02-18", "2026-04-24")),
        SeedOffice(6, "Moolimangalam", "29106866", listOf("2026-02-23", "2026-04-18")),
        SeedOffice(7, "Punnam", "29106939", listOf("2026-02-18", "2026-07-09")),
        SeedOffice(8, "Punnam Chatram", "29106940", listOf("2026-02-23", "2026-05-26")),
        SeedOffice(9, "Vennamalai", "29107013", listOf("2026-02-24", "2026-07-14")),
        SeedOffice(10, "Kadaparai", "29106786", listOf("2026-01-06", "2026-04-09")),
        SeedOffice(11, "Manmangalam", "29106853", listOf("2026-03-27", "2026-07-02")),
        SeedOffice(12, "Nadayanur", "29106879", listOf("2026-02-27", "2026-05-06")),
        SeedOffice(13, "Noyyal", "29106894", listOf("2026-01-09", "2026-04-16")),
        SeedOffice(14, "Semangi", "29106959", listOf("2026-01-07", "2026-04-07")),
        SeedOffice(15, "Vettamangalam", "29107015", listOf("2026-03-03", "2026-05-19")),
        SeedOffice(16, "Dalavapalayam", "29106765", listOf("2026-02-28", "2026-05-20")),
        SeedOffice(17, "East Thavittupalayam", "29106766", listOf("2026-02-25", "2026-05-14")),
        SeedOffice(18, "Kadambankurichi", "29106785", listOf("2026-03-25")),
        SeedOffice(19, "Nanjaypugalur", "29106887", listOf("2026-03-19", "2026-07-18")),
        SeedOffice(20, "Nanniyur", "29106889", listOf("2026-03-12", "2026-07-13")),
        SeedOffice(21, "Punjaipugalur", "29106937", listOf("2026-03-17", "2026-07-15")),
        SeedOffice(22, "Punjaithottakuruchi", "29106938", listOf("2026-03-26", "2026-07-20")),
        SeedOffice(23, "West Orathai", "29107017", listOf("2026-03-07", "2026-07-11")),
        SeedOffice(24, "Mudiganam", "29106867", listOf("2026-03-24", "2026-05-21")),
        SeedOffice(25, "Pallamarudapatti", "29106904", listOf("2026-02-21", "2026-05-05")),
        SeedOffice(26, "Pallapalayam", "29106905", listOf("2026-03-04", "2026-05-08")),
        SeedOffice(27, "Pavithram", "29106914", listOf("2026-02-26", "2026-04-21")),
        SeedOffice(28, "Thumbivadi", "29106986", listOf("2025-01-05", "2026-04-06")),
        SeedOffice(29, "Viswanathapuri", "29107016", listOf("2025-01-12", "2026-04-27"))
    )
}