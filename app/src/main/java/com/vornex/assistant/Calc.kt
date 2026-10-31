package com.vornex.assistant

/** Арифметика голосом: «сколько будет двадцать пять умножить на четыре», «пять процентов от двухсот». */
object Calc {
    private sealed class Item {
        data class Num(val v: Double) : Item()
        data class Op(val c: Char) : Item()
    }

    /** Результат или null, если во фразе нет арифметики. */
    fun tryEvaluate(text: String): Double? {
        val toks = RuNumbers.tokenize(text)
        val items = ArrayList<Item>()
        var i = 0
        while (i < toks.size) {
            when (val t = toks[i]) {
                is RuNumbers.Tok.N -> items.add(Item.Num(t.v))
                is RuNumbers.Tok.W -> {
                    val w = t.s
                    when {
                        w == "плюс" -> items.add(Item.Op('+'))
                        w == "минус" -> items.add(Item.Op('-'))
                        w.startsWith("умнож") || w.startsWith("помнож") -> items.add(Item.Op('*'))
                        w.startsWith("раздел") || w.startsWith("подел") || w == "делить" -> items.add(Item.Op('/'))
                        w.startsWith("процент") -> {
                            items.add(Item.Op('%'))
                            if ((toks.getOrNull(i + 1) as? RuNumbers.Tok.W)?.s == "от") i++
                        }
                        else -> {} // «сколько», «будет», «на» и прочие служебные слова
                    }
                }
            }
            i++
        }
        if (items.size < 3 || items.size % 2 == 0) return null
        for ((k, item) in items.withIndex()) {
            if ((k % 2 == 0) != (item is Item.Num)) return null
        }

        // Сначала умножение, деление и проценты, потом сложение и вычитание.
        val nums = ArrayList<Double>()
        val ops = ArrayList<Char>()
        nums.add((items[0] as Item.Num).v)
        var k = 1
        while (k < items.size) {
            val op = (items[k] as Item.Op).c
            val v = (items[k + 1] as Item.Num).v
            if (op == '*' || op == '/' || op == '%') {
                val a = nums.removeAt(nums.size - 1)
                nums.add(
                    when (op) {
                        '*' -> a * v
                        '/' -> if (v == 0.0) return null else a / v
                        else -> a * v / 100
                    }
                )
            } else {
                ops.add(op)
                nums.add(v)
            }
            k += 2
        }
        var res = nums[0]
        for ((idx, op) in ops.withIndex()) {
            res = if (op == '+') res + nums[idx + 1] else res - nums[idx + 1]
        }
        return res
    }
}
