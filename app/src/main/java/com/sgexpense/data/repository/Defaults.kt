package com.sgexpense.data.repository

import com.sgexpense.data.model.Category

/** Seed categories and Singapore merchant keywords. */
object Defaults {
    data class SeedCategory(val name: String, val emoji: String, val color: Long, val isIncome: Boolean, val keywords: List<String>)

    const val TRANSFERS = "Transfers"
    const val INCOME = "Income"
    const val OTHERS = "Others"

    val categories = listOf(
        SeedCategory("Food & Drinks", "🍜", 0xFFEF6C00, false, listOf(
            "MCDONALD", "KFC", "BURGER KING", "SUBWAY", "STARBUCKS", "TOAST BOX", "YA KUN", "KOPITIAM", "FOOD REPUBLIC",
            "KOUFU", "FOODPANDA", "DELIVEROO", "GRABFOOD", "GRAB FOOD", "MR BEAN", "OLD CHANG KEE", "JOLLIBEE", "PASTAMANIA",
            "SUKIYA", "DIN TAI FUNG", "HAIDILAO", "LIHO", "KOI", "CHAGEE", "MIXUE", "LUCKIN", "FLASH COFFEE", "BREADTALK",
            "FOOD", "CAFE", "COFFEE", "RESTAURANT", "BAKERY", "KITCHEN", "HAWKER", "BISTRO", "EATERY",
        )),
        SeedCategory("Groceries", "🛒", 0xFF2E7D32, false, listOf(
            "FAIRPRICE", "NTUC", "SHENG SIONG", "COLD STORAGE", "GIANT", "DON DON DONKI", "DONKI", "PRIME SUPERMARKET",
            "REDMART", "MUSTAFA", "CS FRESH", "SUPERMARKET", "MART",
        )),
        SeedCategory("Transport", "🚇", 0xFF1565C0, false, listOf(
            "GRAB", "GOJEK", "TADA", "RYDE", "COMFORTDELGRO", "COMFORT", "CDG", "SMRT", "SBS TRANSIT", "TRANSITLINK",
            "BUS/MRT", "EZ-LINK", "EZLINK", "SIMPLYGO", "SHELL", "ESSO", "CALTEX", "SPC", "PARKING", "EPS",
            "BUS", "MRT", "LRT", "EZ LINK", "SIMPLY GO",
        )),
        SeedCategory("Shopping", "🛍️", 0xFF8E24AA, false, listOf(
            "SHOPEE", "LAZADA", "AMAZON", "TAOBAO", "UNIQLO", "ZARA", "H&M", "IKEA", "DAISO", "MUJI", "COURTS",
            "HARVEY NORMAN", "CHALLENGER", "BEST DENKI", "DECATHLON", "POPULAR", "MINISO", "TEMU", "SHEIN", "QOO10",
        )),
        SeedCategory("Bills & Utilities", "💡", 0xFF00838F, false, listOf(
            "SINGTEL", "STARHUB", "M1 LIMITED", "SIMBA", "GIGA", "CIRCLES", "SP SERVICES", "SP GROUP", "GENECO",
            "TOWN COUNCIL", "IRAS", "HDB", "INSURANCE", "PRUDENTIAL", "AIA", "GREAT EASTERN", "NTUC INCOME",
        )),
        SeedCategory("Entertainment", "🎬", 0xFFD81B60, false, listOf(
            "NETFLIX", "SPOTIFY", "DISNEY", "YOUTUBE", "GOLDEN VILLAGE", "GV ", "SHAW", "CATHAY", "STEAM", "PLAYSTATION",
            "NINTENDO", "KLOOK", "SISTIC", "APPLE.COM", "GOOGLE PLAY",
        )),
        SeedCategory("Health", "💊", 0xFFC62828, false, listOf(
            "GUARDIAN", "WATSONS", "UNITY", "CLINIC", "POLYCLINIC", "HOSPITAL", "DENTAL", "PHARMACY", "MEDICAL",
        )),
        SeedCategory("Travel", "✈️", 0xFF5E35B1, false, listOf(
            "SINGAPORE AIRLINES", "SCOOT", "JETSTAR", "AIRASIA", "AGODA", "BOOKING.COM", "EXPEDIA", "TRIP.COM", "AIRBNB", "HOTEL",
        )),
        SeedCategory(TRANSFERS, "🔁", 0xFF546E7A, false, emptyList()),
        SeedCategory(OTHERS, "📦", 0xFF757575, false, emptyList()),
        SeedCategory(INCOME, "💰", 0xFF43A047, true, listOf("SALARY", "PAYROLL", "GIRO SALARY")),
        SeedCategory("Cashback & Refunds", "🎁", 0xFF00897B, true, listOf("CASHBACK", "REBATE")),
    )

    fun toEntities(): List<Category> = categories.mapIndexed { i, s ->
        Category(name = s.name, emoji = s.emoji, color = s.color, isIncome = s.isIncome, sortOrder = i)
    }

    val emojiChoices = listOf(
        "🍜", "🍔", "☕", "🛒", "🚇", "🚕", "⛽", "🛍️", "👕", "💡", "📱", "🏠", "🎬", "🎮", "💊", "🏥", "✈️", "🏨",
        "🎓", "📚", "🐶", "👶", "💇", "🏋️", "🎁", "💰", "💳", "🔁", "📦", "❤️",
    )

    val colorChoices = listOf(
        0xFFEF6C00, 0xFF2E7D32, 0xFF1565C0, 0xFF8E24AA, 0xFF00838F, 0xFFD81B60, 0xFFC62828, 0xFF5E35B1,
        0xFF546E7A, 0xFF757575, 0xFF43A047, 0xFF00897B, 0xFFF9A825, 0xFF6D4C41,
    )
}
