package com.leap8.offline.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.leap8.offline.domain.*
import java.time.LocalDate

class OfflineDatabase(context: Context) : SQLiteOpenHelper(context, "leap8_offline.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE word_progress(
                word_id TEXT PRIMARY KEY,
                repetitions INTEGER NOT NULL,
                interval_days INTEGER NOT NULL,
                ease_factor REAL NOT NULL,
                due_epoch_day INTEGER NOT NULL,
                lapses INTEGER NOT NULL,
                last_rating TEXT
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE practice_session(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                skill TEXT NOT NULL,
                score REAL NOT NULL,
                duration_seconds INTEGER NOT NULL,
                created_epoch_day INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE essay_history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id TEXT NOT NULL,
                essay TEXT NOT NULL,
                overall_band REAL NOT NULL,
                engine TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_session_day ON practice_session(created_epoch_day)")
        db.execSQL("CREATE INDEX idx_word_due ON word_progress(due_epoch_day)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun wordProgress(wordId: String): WordProgress {
        readableDatabase.query(
            "word_progress", null, "word_id = ?", arrayOf(wordId), null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return WordProgress(wordId = wordId)
            val ratingName = cursor.getString(cursor.getColumnIndexOrThrow("last_rating"))
            return WordProgress(
                wordId = wordId,
                repetitions = cursor.getInt(cursor.getColumnIndexOrThrow("repetitions")),
                intervalDays = cursor.getInt(cursor.getColumnIndexOrThrow("interval_days")),
                easeFactor = cursor.getDouble(cursor.getColumnIndexOrThrow("ease_factor")),
                dueEpochDay = cursor.getLong(cursor.getColumnIndexOrThrow("due_epoch_day")),
                lapses = cursor.getInt(cursor.getColumnIndexOrThrow("lapses")),
                lastRating = ratingName?.let { runCatching { ReviewRating.valueOf(it) }.getOrNull() }
            )
        }
    }

    fun allWordProgress(): Map<String, WordProgress> {
        val result = linkedMapOf<String, WordProgress>()
        readableDatabase.query("word_progress", null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow("word_id"))
                val ratingName = cursor.getString(cursor.getColumnIndexOrThrow("last_rating"))
                result[id] = WordProgress(
                    wordId = id,
                    repetitions = cursor.getInt(cursor.getColumnIndexOrThrow("repetitions")),
                    intervalDays = cursor.getInt(cursor.getColumnIndexOrThrow("interval_days")),
                    easeFactor = cursor.getDouble(cursor.getColumnIndexOrThrow("ease_factor")),
                    dueEpochDay = cursor.getLong(cursor.getColumnIndexOrThrow("due_epoch_day")),
                    lapses = cursor.getInt(cursor.getColumnIndexOrThrow("lapses")),
                    lastRating = ratingName?.let { runCatching { ReviewRating.valueOf(it) }.getOrNull() }
                )
            }
        }
        return result
    }

    fun saveWordProgress(progress: WordProgress) {
        val values = ContentValues().apply {
            put("word_id", progress.wordId)
            put("repetitions", progress.repetitions)
            put("interval_days", progress.intervalDays)
            put("ease_factor", progress.easeFactor)
            put("due_epoch_day", progress.dueEpochDay)
            put("lapses", progress.lapses)
            put("last_rating", progress.lastRating?.name)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "word_progress", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
        check(rowId != -1L) { "无法保存词汇进度，请检查设备可用空间" }
    }

    fun logSession(skill: Skill, score: Double, durationSeconds: Int) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("skill", skill.name)
            put("score", score.coerceIn(0.0, 9.0))
            put("duration_seconds", durationSeconds.coerceAtLeast(1))
            put("created_epoch_day", LocalDate.now().toEpochDay())
            put("created_at", now)
        }
        writableDatabase.insertOrThrow("practice_session", null, values)
    }

    fun saveEssay(taskId: String, essay: String, score: EssayScore) {
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("essay", essay)
            put("overall_band", score.overallBand)
            put("engine", score.engine)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("essay_history", null, values)
    }

    fun dashboard(totalVocabulary: Int): DashboardSnapshot {
        val today = LocalDate.now().toEpochDay()
        val todaySeconds = scalarLong(
            "SELECT COALESCE(SUM(duration_seconds), 0) FROM practice_session WHERE created_epoch_day = ?",
            arrayOf(today.toString())
        )
        val sessions = scalarLong("SELECT COUNT(*) FROM practice_session")
        val learnedWords = scalarLong("SELECT COUNT(*) FROM word_progress WHERE repetitions > 0")
        val masteredWords = scalarLong("SELECT COUNT(*) FROM word_progress WHERE repetitions >= 5 OR interval_days >= 21")
        val scheduledDue = scalarLong(
            "SELECT COUNT(*) FROM word_progress WHERE due_epoch_day <= ?",
            arrayOf(today.toString())
        )
        val storedWords = scalarLong("SELECT COUNT(*) FROM word_progress")
        val dueWords = (scheduledDue + (totalVocabulary - storedWords).coerceAtLeast(0)).toInt()
        val skillScores = Skill.entries.associateWith { skill ->
            readableDatabase.rawQuery(
                "SELECT AVG(score) FROM practice_session WHERE skill = ?",
                arrayOf(skill.name)
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    (cursor.getDouble(0) / 9.0).toFloat().coerceIn(0.08f, 1f)
                } else 0.35f
            }
        }
        val average = skillScores.values.average().toFloat()
        val estimatedBand = ((3.0 + average * 5.0) * 2).toInt() / 2.0
        return DashboardSnapshot(
            streakDays = calculateStreak(today),
            todayMinutes = (todaySeconds / 60).toInt(),
            dueWords = dueWords,
            learnedWords = learnedWords.toInt(),
            totalWords = totalVocabulary,
            newWords = (totalVocabulary - learnedWords).coerceAtLeast(0).toInt(),
            learningWords = (learnedWords - masteredWords).coerceAtLeast(0).toInt(),
            masteredWords = masteredWords.toInt(),
            completedSessions = sessions.toInt(),
            estimatedBand = estimatedBand.coerceIn(3.0, 8.5),
            skillScores = skillScores
        )
    }

    private fun calculateStreak(today: Long): Int {
        val activeDays = mutableSetOf<Long>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT created_epoch_day FROM practice_session ORDER BY created_epoch_day DESC",
            null
        ).use { cursor -> while (cursor.moveToNext()) activeDays += cursor.getLong(0) }
        if (activeDays.isEmpty()) return 1
        var day = if (today in activeDays) today else today - 1
        var streak = 0
        while (day in activeDays) { streak++; day-- }
        return streak.coerceAtLeast(1)
    }

    private fun scalarLong(sql: String, args: Array<String>? = null): Long =
        readableDatabase.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
}
