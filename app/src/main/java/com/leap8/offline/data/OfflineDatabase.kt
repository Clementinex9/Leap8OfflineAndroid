package com.leap8.offline.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.leap8.offline.core.Leap8CoreNative
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
        val db = readableDatabase

        var wpReps = IntArray(0)
        var wpIntervals = IntArray(0)
        var wpDueDays = LongArray(0)
        db.rawQuery("SELECT repetitions, interval_days, due_epoch_day FROM word_progress", null).use { cursor ->
            val count = cursor.count.coerceAtLeast(0)
            wpReps = IntArray(count)
            wpIntervals = IntArray(count)
            wpDueDays = LongArray(count)
            var idx = 0
            while (cursor.moveToNext() && idx < count) {
                wpReps[idx] = cursor.getInt(0)
                wpIntervals[idx] = cursor.getInt(1)
                wpDueDays[idx] = cursor.getLong(2)
                idx++
            }
        }

        val skillIndexByName = Skill.entries.withIndex().associate { it.value.name to it.index }
        var sessSkills = IntArray(0)
        var sessScores = DoubleArray(0)
        var sessDurations = IntArray(0)
        var sessDays = LongArray(0)
        db.rawQuery("SELECT skill, score, duration_seconds, created_epoch_day FROM practice_session", null).use { cursor ->
            val count = cursor.count.coerceAtLeast(0)
            sessSkills = IntArray(count)
            sessScores = DoubleArray(count)
            sessDurations = IntArray(count)
            sessDays = LongArray(count)
            var idx = 0
            while (cursor.moveToNext() && idx < count) {
                sessSkills[idx] = skillIndexByName[cursor.getString(0)] ?: -1
                sessScores[idx] = cursor.getDouble(1)
                sessDurations[idx] = cursor.getInt(2)
                sessDays[idx] = cursor.getLong(3)
                idx++
            }
        }

        val metrics = Leap8CoreNative.computeDashboard(
            totalVocabulary = totalVocabulary,
            todayEpochDay = today,
            wpReps = wpReps,
            wpIntervals = wpIntervals,
            wpDueDays = wpDueDays,
            sessSkills = sessSkills,
            sessScores = sessScores,
            sessDurations = sessDurations,
            sessDays = sessDays
        )
        val skillScores = Skill.entries.mapIndexed { index, skill ->
            skill to metrics.skillNorms[index]
        }.toMap()
        return DashboardSnapshot(
            streakDays = metrics.streakDays,
            todayMinutes = metrics.todayMinutes,
            dueWords = metrics.dueWords,
            learnedWords = metrics.learnedWords,
            totalWords = metrics.totalWords,
            newWords = metrics.newWords,
            learningWords = metrics.learningWords,
            masteredWords = metrics.masteredWords,
            completedSessions = metrics.completedSessions,
            estimatedBand = metrics.estimatedBand,
            skillScores = skillScores
        )
    }
}
