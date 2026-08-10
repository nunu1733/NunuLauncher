package app.lawnchair.organizer.planning

internal fun compareUtf8Bytes(a: String, b: String): Int {
    val aBytes = a.toByteArray(Charsets.UTF_8)
    val bBytes = b.toByteArray(Charsets.UTF_8)
    val minLen = minOf(aBytes.size, bBytes.size)
    for (i in 0 until minLen) {
        val ua = aBytes[i].toInt() and 0xFF
        val ub = bBytes[i].toInt() and 0xFF
        if (ua != ub) return ua - ub
    }
    return aBytes.size - bBytes.size
}

data class ItemId(val value: String) : Comparable<ItemId> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: ItemId): Int = compareUtf8Bytes(value, other.value)
}

data class ProfileId(val value: String) : Comparable<ProfileId> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: ProfileId): Int = compareUtf8Bytes(value, other.value)
}

data class PageId(val value: String) : Comparable<PageId> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: PageId): Int = compareUtf8Bytes(value, other.value)
}

data class FolderId(val value: String) : Comparable<FolderId> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: FolderId): Int = compareUtf8Bytes(value, other.value)
}

data class AppPairId(val value: String) : Comparable<AppPairId> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: AppPairId): Int = compareUtf8Bytes(value, other.value)
}

data class SnapPositionToken(val value: String) : Comparable<SnapPositionToken> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: SnapPositionToken): Int = compareUtf8Bytes(value, other.value)
}

data class CategoryId(val value: String) : Comparable<CategoryId> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: CategoryId): Int = compareUtf8Bytes(value, other.value)
}

data class TaxonomyVersion(val value: String) : Comparable<TaxonomyVersion> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: TaxonomyVersion): Int = compareUtf8Bytes(value, other.value)
}

data class RevisionId(val value: String) : Comparable<RevisionId> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: RevisionId): Int = compareUtf8Bytes(value, other.value)
}

data class RuleVersion(val value: String) : Comparable<RuleVersion> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: RuleVersion): Int = compareUtf8Bytes(value, other.value)
}

data class ComponentKey(val value: String) : Comparable<ComponentKey> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: ComponentKey): Int = compareUtf8Bytes(value, other.value)
}

data class PackageName(val value: String) : Comparable<PackageName> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: PackageName): Int = compareUtf8Bytes(value, other.value)
}

data class ShortcutId(val value: String) : Comparable<ShortcutId> {
    init {
        require(value.isNotEmpty())
    }

    override fun compareTo(other: ShortcutId): Int = compareUtf8Bytes(value, other.value)
}

data class KindCode(val value: Int) : Comparable<KindCode> {
    override fun compareTo(other: KindCode): Int = value.compareTo(other.value)
}

data class ContainerCode(val value: Int) : Comparable<ContainerCode> {
    override fun compareTo(other: ContainerCode): Int = value.compareTo(other.value)
}

data class AppWidgetId(val value: Int) : Comparable<AppWidgetId> {
    override fun compareTo(other: AppWidgetId): Int = value.compareTo(other.value)
}

data class PageOrder(val value: String) : Comparable<PageOrder> {
    init {
        require(value.isNotEmpty())
        require(value.all { it in '0'..'9' })
        require(value == "0" || value.first() != '0')
    }

    constructor(value: Int) : this(value.toString())

    override fun compareTo(other: PageOrder): Int = value.length.compareTo(other.value.length).takeIf { it != 0 }
        ?: value.compareTo(other.value)

    operator fun plus(increment: Int): PageOrder {
        require(increment >= 0)
        if (increment == 0) return this

        var sourceIndex = value.lastIndex
        var remainingIncrement = increment
        var carry = 0
        val reversed = StringBuilder(maxOf(value.length, increment.toString().length) + 1)
        while (sourceIndex >= 0 || remainingIncrement > 0 || carry > 0) {
            val sourceDigit = if (sourceIndex >= 0) value[sourceIndex--] - '0' else 0
            val incrementDigit = remainingIncrement % 10
            remainingIncrement /= 10
            val sum = sourceDigit + incrementDigit + carry
            reversed.append(('0'.code + (sum % 10)).toChar())
            carry = sum / 10
        }
        return PageOrder(reversed.reverse().toString())
    }
}

data class NewPageOrdinal(val value: Int) : Comparable<NewPageOrdinal> {
    init {
        require(value >= 0)
    }

    override fun compareTo(other: NewPageOrdinal): Int = value.compareTo(other.value)
}

data class NewFolderOrdinal(val value: Int) : Comparable<NewFolderOrdinal> {
    init {
        require(value >= 0)
    }

    override fun compareTo(other: NewFolderOrdinal): Int = value.compareTo(other.value)
}

data class GridCell(val x: Int, val y: Int)

data class GridSpan(val width: Int, val height: Int)

data class PageRef(val pageId: PageId) : PageTargetRef

data class NewPageRef(val ordinal: NewPageOrdinal) : PageTargetRef

sealed interface PageTargetRef

data class FolderRef(val folderId: FolderId) : FolderTargetRef

data class NewFolderRef(val ordinal: NewFolderOrdinal) : FolderTargetRef

sealed interface FolderTargetRef

data class AppPairRef(val appPairId: AppPairId)
