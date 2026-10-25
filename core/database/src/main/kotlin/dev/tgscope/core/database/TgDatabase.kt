package dev.tgscope.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChannelEntity::class,
        PostEntity::class,
        CommentEntity::class,
        CommentFts::class,
        UserEntity::class,
        ReactionEntity::class,
        SyncRunEntity::class,
        AnalysisEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TgDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun posts(): PostDao
    abstract fun comments(): CommentDao
    abstract fun users(): UserDao
    abstract fun reactions(): ReactionDao
    abstract fun stats(): StatsDao
    abstract fun syncRuns(): SyncRunDao
    abstract fun analyses(): AnalysisDao

    companion object {
        fun create(context: Context): TgDatabase =
            Room.databaseBuilder(context.applicationContext, TgDatabase::class.java, "tgscope.db")
                .build()
    }
}
