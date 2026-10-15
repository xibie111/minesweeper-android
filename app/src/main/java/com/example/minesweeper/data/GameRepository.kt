package com.example.minesweeper.data

import android.content.Context
import android.content.SharedPreferences
import com.example.minesweeper.core.GameConfig
import com.example.minesweeper.core.GameEngine
import com.example.minesweeper.core.GamePhase
import com.example.minesweeper.core.GameState
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val haptics: Boolean = true,
)

data class GameRecord(
    val key: String,
    val name: String,
    val completed: Int,
    val wins: Int,
    val bestMillis: Long?,
)

/** One envelope makes the game, its finalization ID and its statistics atomic. */
data class PersistedSession(
    val game: GameState,
    val settings: AppSettings,
    val records: List<GameRecord>,
    val finalizedGameIds: Set<String>,
)

data class LoadedSession(
    val session: PersistedSession,
    val notice: String? = null,
)

/** Pure completion bookkeeping, shared by the ViewModel and JVM tests. */
internal fun finalizeGame(session: PersistedSession): PersistedSession {
    val game = session.game
    if (!game.isFinished || game.id in session.finalizedGameIds) return session
    val previous = session.records.firstOrNull { it.key == game.config.key }
        ?: GameRecord(game.config.key, game.config.displayName, 0, 0, null)
    val won = game.phase == GamePhase.WON
    val best = if (won) {
        previous.bestMillis?.let { minOf(it, game.elapsedMillis) } ?: game.elapsedMillis
    } else previous.bestMillis
    fun increment(value: Int) = if (value == Int.MAX_VALUE) value else value + 1
    val record = previous.copy(
        name = game.config.displayName,
        completed = increment(previous.completed),
        wins = if (won) increment(previous.wins) else previous.wins,
        bestMillis = best,
    )
    return session.copy(
        records = (session.records.filterNot { it.key == record.key } + record).sortedBy { it.key },
        // Only the current terminal board can be restored; the bounded recent
        // ledger prevents unbounded growth while retaining that board's ID.
        finalizedGameIds = (session.finalizedGameIds + game.id).toList().takeLast(128).toSet(),
    )
}

/**
 * A small, versioned local snapshot. Calls to [save] run on the application's
 * shared ordered IO writer; no disk operation is performed by a board gesture.
 */
class GameRepository(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("minesweeper_session", Context.MODE_PRIVATE),
    )

    fun load(): LoadedSession {
        val fallback = PersistedSession(
            game = GameEngine.newGame(GameConfig.BEGINNER),
            settings = AppSettings(),
            records = emptyList(),
            finalizedGameIds = emptySet(),
        )
        val raw = try {
            preferences.getString(SNAPSHOT_KEY, null)
        } catch (_: RuntimeException) {
            return LoadedSession(fallback, "本地存档无法读取，已开始新对局。")
        } ?: return LoadedSession(fallback)

        val envelope = try {
            JSONObject(raw).also { require(it.strictInt("version") == VERSION) }
        } catch (_: Exception) {
            return LoadedSession(fallback, "本地存档损坏或版本不兼容，已恢复默认状态。")
        }

        val notices = mutableListOf<String>()
        val settings = try {
            val value = envelope.getJSONObject("settings")
            AppSettings(
                theme = ThemeChoice.valueOf(value.getString("theme")),
                haptics = value.get("haptics").let {
                    require(it is Boolean)
                    it
                },
            )
        } catch (_: Exception) {
            notices += "设置已恢复默认值"
            fallback.settings
        }

        val records = try {
            val values = envelope.getJSONArray("records")
            List(values.length()) { index ->
                val record = values.getJSONObject(index)
                val completed = record.strictInt("completed")
                val wins = record.strictInt("wins")
                val best = if (record.isNull("bestMillis")) null else record.strictLong("bestMillis")
                val key = record.getString("key")
                val name = record.getString("name")
                require(key.isNotBlank() && name.isNotBlank())
                require(completed >= 0 && wins in 0..completed)
                require(best == null || (wins > 0 && best >= 0L))
                GameRecord(key, name, completed, wins, best)
            }.also { valuesList ->
                require(valuesList.map { it.key }.toSet().size == valuesList.size)
            }
        } catch (_: Exception) {
            notices += "统计存档损坏，已重置统计"
            emptyList()
        }

        // Treat the completion ledger and current game as one consistency unit.
        // A terminal game without its ledger entry must never be counted again.
        val gameAndIds = try {
            val idsJson = envelope.getJSONArray("finalizedGameIds")
            val ids = List(idsJson.length()) { index ->
                idsJson.getString(index).also { require(it.isNotBlank() && it.length <= 128) }
            }.toSet()
            require(ids.size == idsJson.length())
            val game = readGame(envelope.getJSONObject("game"))
            require(game.isFinished == (game.id in ids))
            game to ids
        } catch (_: Exception) {
            notices += "对局存档损坏，已开始新对局"
            fallback.game to emptySet()
        }

        return LoadedSession(
            session = PersistedSession(gameAndIds.first, settings, records, gameAndIds.second),
            notice = notices.takeIf { it.isNotEmpty() }?.joinToString("；", postfix = "。"),
        )
    }

    /** Returns false if the durable atomic write failed. Called only on IO. */
    fun save(session: PersistedSession): Boolean = try {
        val settings = JSONObject()
            .put("theme", session.settings.theme.name)
            .put("haptics", session.settings.haptics)
        val records = JSONArray()
        session.records.forEach { record ->
            records.put(
                JSONObject()
                    .put("key", record.key)
                    .put("name", record.name)
                    .put("completed", record.completed)
                    .put("wins", record.wins)
                    .put("bestMillis", record.bestMillis ?: JSONObject.NULL),
            )
        }
        val ids = JSONArray()
        session.finalizedGameIds.forEach { ids.put(it) }
        val envelope = JSONObject()
            .put("version", VERSION)
            .put("settings", settings)
            .put("records", records)
            .put("finalizedGameIds", ids)
            .put("game", writeGame(session.game))
        preferences.edit().putString(SNAPSHOT_KEY, envelope.toString()).commit()
    } catch (_: Exception) {
        false
    }

    private fun writeGame(game: GameState): JSONObject {
        fun indices(values: Set<Int>) = JSONArray().also { array ->
            values.sorted().forEach { array.put(it) }
        }
        return JSONObject()
            .put("id", game.id)
            .put("rows", game.config.rows)
            .put("cols", game.config.cols)
            .put("mineCount", game.config.mineCount)
            .put("seed", game.seed)
            .put("mines", indices(game.mines))
            .put("revealed", indices(game.revealed))
            .put("flags", indices(game.flags))
            .put("phase", game.phase.name)
            .put("explodedIndex", game.explodedIndex ?: JSONObject.NULL)
            .put("elapsedMillis", game.elapsedMillis)
    }

    private fun readGame(value: JSONObject): GameState {
        val config = GameConfig(
            rows = value.strictInt("rows"),
            cols = value.strictInt("cols"),
            mineCount = value.strictInt("mineCount"),
        )
        require(config.validationError() == null)
        val count = config.rows * config.cols
        fun indices(key: String): Set<Int> {
            val array = value.getJSONArray(key)
            require(array.length() <= count)
            val numbers = List(array.length()) { index ->
                array.get(index).strictLong().also { require(it in 0L until count.toLong()) }.toInt()
            }
            return numbers.toSet().also { require(it.size == numbers.size) }
        }
        val id = value.getString("id")
        require(id.isNotBlank() && id.length <= 128)
        val game = GameState(
            id = id,
            config = config,
            seed = value.strictLong("seed"),
            mines = indices("mines"),
            revealed = indices("revealed"),
            flags = indices("flags"),
            phase = GamePhase.valueOf(value.getString("phase")),
            explodedIndex = if (value.isNull("explodedIndex")) null else value.strictInt("explodedIndex"),
            elapsedMillis = value.strictLong("elapsedMillis"),
        )
        require(game.elapsedMillis >= 0L)
        require(GameEngine.isValid(game))
        return game
    }

    private fun JSONObject.strictLong(key: String): Long = get(key).strictLong()

    private fun JSONObject.strictInt(key: String): Int = strictLong(key).let { number ->
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        number.toInt()
    }

    private fun Any.strictLong(): Long {
        require(this is Number)
        return toString().toLongOrNull() ?: error("Expected an integer")
    }

    private companion object {
        const val VERSION = 1
        const val SNAPSHOT_KEY = "snapshot"
    }
}
