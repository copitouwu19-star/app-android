package com.example.myapp

import android.content.Context
import androidx.room.*

// ── Entidades ─────────────────────────────────────────────────────────────────

@Entity(tableName = "preferencias")
data class PreferenciaEntity(
    @PrimaryKey(autoGenerate = true) val idPreferencia: Long = 0,
    val distanciaAlerta: String,
    val velocidadVoz: String,
    val vibracionActivada: Boolean
)

@Entity(
    tableName = "sesiones",
    foreignKeys = [ForeignKey(
        entity = PreferenciaEntity::class,
        parentColumns = ["idPreferencia"],
        childColumns = ["idPreferencia"],
        onDelete = ForeignKey.SET_NULL
    )]
)
data class SesionEntity(
    @PrimaryKey(autoGenerate = true) val idSesion: Long = 0,
    val fechaHoraInicio: Long,
    @ColumnInfo(index = true) val idPreferencia: Long?
)

@Entity(
    tableName = "ubicaciones",
    foreignKeys = [ForeignKey(
        entity = SesionEntity::class,
        parentColumns = ["idSesion"],
        childColumns = ["idSesion"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class UbicacionEntity(
    @PrimaryKey(autoGenerate = true) val idUbicacion: Long = 0,
    val latitud: Double,
    val longitud: Double,
    val fechaHora: Long,
    @ColumnInfo(index = true) val idSesion: Long
)

// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface PreferenciaDao {
    @Insert fun insert(pref: PreferenciaEntity): Long
    @Update fun update(pref: PreferenciaEntity)
    @Query("SELECT * FROM preferencias ORDER BY idPreferencia DESC LIMIT 1")
    fun getLast(): PreferenciaEntity?
}

@Dao
interface SesionDao {
    @Insert fun insert(sesion: SesionEntity): Long
    @Query("SELECT * FROM sesiones ORDER BY idSesion DESC LIMIT 20")
    fun getRecent(): List<SesionEntity>
}

@Dao
interface UbicacionDao {
    @Insert fun insert(ub: UbicacionEntity)
    @Query("SELECT * FROM ubicaciones WHERE idSesion = :sesionId ORDER BY fechaHora ASC")
    fun getBySesion(sesionId: Long): List<UbicacionEntity>
}

// ── Base de datos ─────────────────────────────────────────────────────────────

@Database(
    entities = [PreferenciaEntity::class, SesionEntity::class, UbicacionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun preferenciaDao(): PreferenciaDao
    abstract fun sesionDao(): SesionDao
    abstract fun ubicacionDao(): UbicacionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "visual_nav.db"
                ).build().also { INSTANCE = it }
            }
    }
}
