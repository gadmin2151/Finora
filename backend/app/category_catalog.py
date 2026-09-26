"""Everyday categories and conservative receipt vocabulary (Romanian, Russian, English)."""

import re
import unicodedata

BASE_CATEGORIES = [
    ("Продукты", "shopping-basket", "#18a999"),
    ("Рестораны и кафе", "coffee", "#ed9753"),
    ("Дом", "house", "#7376d7"),
    ("Автомобиль", "car", "#4a93cf"),
    ("Транспорт", "train", "#64abc2"),
    ("Здоровье", "heart", "#dc7590"),
    ("Покупки", "shopping-bag", "#bf8cc8"),
    ("Связь и подписки", "repeat", "#d5ad4b"),
    ("Образование", "book", "#689d7a"),
    ("Отдых", "plane", "#4db6ba"),
    ("Подарки", "gift", "#ce7c6c"),
    ("Прочее", "tag", "#98a1b2"),
]

DAILY_CATEGORIES = [
    ("Овощи и фрукты", "apple", "#669c45"),
    ("Мясо и птица", "beef", "#ce746c"),
    ("Рыба и морепродукты", "fish", "#4a93cf"),
    ("Молочные продукты и яйца", "milk", "#769dc6"),
    ("Хлеб и выпечка", "croissant", "#bb8b48"),
    ("Крупы, макароны и бакалея", "wheat", "#aa9448"),
    ("Сладости", "candy", "#ca78a1"),
    ("Снеки", "popcorn", "#cf924a"),
    ("Кофе и чай", "coffee", "#a67c52"),
    ("Вода и напитки", "cup-soda", "#4b9ead"),
    ("Энергетики", "zap", "#b19230"),
    ("Алкоголь", "wine", "#a46b94"),
    ("Сигареты и табак", "cigarette", "#8f879c"),
    ("Готовая еда", "utensils", "#ce8764"),
    ("Бытовая химия", "spray-can", "#698dcd"),
    ("Гигиена и уход", "bath", "#54a8a2"),
    ("Товары для дома", "lamp", "#8c87b1"),
    ("Для детей", "baby", "#bb82b3"),
    ("Питомцы", "paw-print", "#aa8b61"),
    ("Одежда и обувь", "shirt", "#8b83ca"),
    ("Косметика", "sparkles", "#c37791"),
    ("Лекарства", "pill", "#c56e7e"),
]

DEFAULT_CATEGORIES = [*BASE_CATEGORIES, *DAILY_CATEGORIES]
BROAD_CATEGORIES = {"Продукты", "Покупки", "Прочее", "Дом", "Здоровье"}

# Ordered from specific to general. Match at a word boundary, never arbitrary
# substrings: "ceapa" is onion, not "apa" (water), and "tea" is not "steak".
_VOCABULARY = [
    (
        "Сигареты и табак",
        r"tigar\w*|tutun\w*|cigaret\w*|tobacco|terea|heets|iqos|marlboro|winston|kent|camel|vape\w*|сигарет\w*|табак\w*|стик\w*|вейп\w*",
    ),
    (
        "Для детей",
        r"scutec\w*|pampers|huggies|jucari\w*|подгузник\w*|памперс\w*|игрушк\w*|детск\w*\s+(?:питан\w*|смес\w*)",
    ),
    (
        "Питомцы",
        r"whiskas|pedigree|purina|hrana\s+(?:pentru\s+)?(?:pisic\w*|cain\w*)|корм\s+для\s+(?:кош\w*|собак\w*)|наполнитель\s+для\s+туалета",
    ),
    (
        "Бытовая химия",
        r"detergent\w*|inalbitor\w*|balsam\s+(?:de\s+)?rufe|fairy|domestos|порошок\s+стиральн\w*|стиральн\w*\s+порошок|отбеливател\w*|средство\s+для\s+(?:мытья|посуды|стирки|уборки)",
    ),
    (
        "Гигиена и уход",
        r"sapun\w*|sampon\w*|pasta\s+de\s+dinti|hartie\s+igienica|servetel\w*|deodorant\w*|мыло|шампун\w*|зубн\w*\s+(?:паст\w*|щетк\w*)|туалетн\w*\s+бумаг\w*|салфетк\w*|дезодорант\w*",
    ),
    (
        "Косметика",
        r"ruj\w*|rimel\w*|fond\s+de\s+ten|parfum\w*|помад\w*|тушь|тональн\w*\s+крем\w*|духи",
    ),
    (
        "Лекарства",
        r"paracetamol\w*|ibuprofen\w*|medicament\w*|vitamin\w*|парацетамол\w*|ибупрофен\w*|витамин\w*|лекарств\w*",
    ),
    (
        "Товары для дома",
        r"punga|pungi|punqa|baterii|bec\w*|saci\s+(?:de\s+)?gunoi|пакет\w*|батарейк\w*|лампочк\w*|пленка\s+пищевая|фольга",
    ),
    (
        "Одежда и обувь",
        r"tricou\w*|pantalon\w*|sosete|incaltaminte|футболк\w*|брюк\w*|носки|обувь|кроссовк\w*",
    ),
    (
        "Энергетики",
        r"energizant\w*|energy\s+drink|red\s+b[uo]ll|burn|monster\s+energy|энергетик\w*|энергетическ\w*\s+напит\w*|ред\s+булл",
    ),
    (
        "Алкоголь",
        r"bere|beer|vin\s+(?:alb|rosu|rose|sec|demi\w*)|wine|vodka|whisk\w*|cognac|divin|coniac|prosecco|sampanie|пиво|водк\w*|вино|виски|коньяк|шампанск\w*",
    ),
    (
        "Кофе и чай",
        r"cafea\w*|coffee|ceai\w*|tea|nescafe|jacobs|lavazza|cappuccino|espresso|latte|кофе|чаи|чая|чаю|капучино|эспрессо|латте",
    ),
    (
        "Сладости",
        r"biscuit\w*|ciocola\w*|bomboan\w*|napolitan\w*|inghetat\w*|prajitur\w*|tort\w*|brinz[.\w]*\s*glaz\w*|branz[.\w]*\s*glaz\w*|candy|chocolate|cookies|печень\w*|конфет\w*|шоколад\w*|вафл\w*|морожен\w*|торт\w*|сырок\w*\s+глаз\w*|глаз\w*\s+сыр\w*",
    ),
    (
        "Снеки",
        r"chips\w*|cips\w*|popcorn|crackers|covrigei|nuci|arahide|alune|seminte|чипс\w*|сухарик\w*|попкорн|орех\w*|семечк\w*|снек\w*",
    ),
    (
        "Вода и напитки",
        r"apa|bautur\w*|suc\w*|limonad\w*|cola|fanta|sprite|pepsi|water|juice|вода|воды|воду|сок\w*|лимонад\w*|напит\w*",
    ),
    (
        "Готовая еда",
        r"pizza|sandwich|burger\w*|sushi|shaorma|shawarma|пицц\w*|сэндвич\w*|бургер\w*|суши|шаурм\w*|шаверм\w*",
    ),
    (
        "Молочные продукты и яйца",
        r"lapte|lactat\w*|branza|brinza|cascaval|smantana|iaurt\w*|chefir|unt|oua|milk|cheese|yogurt|eggs|молок\w*|кефир\w*|иогурт\w*|сметан\w*|творог\w*|сыр\w*|яиц\w*|яицо|сливочн\w*\s+масл\w*|сгущ\w*",
    ),
    (
        "Рыба и морепродукты",
        r"peste|somon|ton|hering|macrou|crevet\w*|fish|salmon|tuna|shrimp\w*|рыб\w*|лосос\w*|семг\w*|сельд\w*|скумбри\w*|кревет\w*|тунец",
    ),
    (
        "Мясо и птица",
        r"carne|pui|porc|vita|curcan|salam\w*|carnat\w*|crenvurst\w*|bacon|meat|chicken|pork|beef|мяс\w*|куриц\w*|свинин\w*|говядин\w*|индейк\w*|колбас\w*|сосиск\w*|бекон\w*",
    ),
    (
        "Хлеб и выпечка",
        r"paine|franzel\w*|chifla|chifle|croissant\w*|covrig\w*|placint\w*|bread|хлеб\w*|батон\w*|булочк\w*|круассан\w*|лаваш\w*",
    ),
    (
        "Крупы, макароны и бакалея",
        r"orez|hrisca|paste|macaroan\w*|faina|ulei|zahar|sare|ovaz|rice|pasta|flour|sugar|рис|греч\w*|макарон\w*|мука|муки|сахар|соль|овсян\w*|масло\s+(?:подсолнеч\w*|оливков\w*)",
    ),
    (
        "Овощи и фрукты",
        r"portocal\w*|mandarin\w*|andarin\w*|banan\w*|cartof\w*|ceapa|morcov\w*|rosi[ei]\w*|castrav\w*|mar|mere|lama[ie]\w*|pepene|strugur\w*|capsun\w*|avocado|varza|usturoi|legume|fructe|orange\w*|apple\w*|potato\w*|tomato\w*|onion\w*|апельсин\w*|мандарин\w*|банан\w*|картоф\w*|лук|морков\w*|помидор\w*|томат\w*|огур\w*|яблок\w*|лимон\w*|арбуз\w*|виноград\w*|клубник\w*|капуст\w*|чеснок\w*",
    ),
    ("Автомобиль", r"benzina|motorina|diesel|бензин\w*|дизел\w*"),
    ("Связь и подписки", r"plata\s+servicii|abonament\w*|internet|интернет|подписк\w*"),
]
_MATCHERS = [(name, re.compile(r"(?<!\w)(?:" + words + r")(?!\w)")) for name, words in _VOCABULARY]
_FALLBACKS = {
    **{name: "Продукты" for name, _, _ in DAILY_CATEGORIES[:12]},
    "Готовая еда": "Продукты",
    "Бытовая химия": "Дом",
    "Товары для дома": "Покупки",
    "Гигиена и уход": "Здоровье",
    "Лекарства": "Здоровье",
}


def category_matches(name: str) -> list[str]:
    text = "".join(
        c for c in unicodedata.normalize("NFKD", name.casefold()) if not unicodedata.combining(c)
    )
    for category, matcher in _MATCHERS:
        if matcher.search(text[:300]):
            return [category, _FALLBACKS.get(category, "Покупки")]
    return []
