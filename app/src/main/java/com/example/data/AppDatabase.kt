package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.AdBlockCategory
import com.example.model.AdBlockRule
import com.example.model.ProtocolType
import com.example.model.SecurityType
import com.example.model.SshAuthMethod
import com.example.model.TransportType
import com.example.model.UpstreamProxyType
import com.example.model.VpnProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "vpn_profiles")
data class ProfileEntity(
  @PrimaryKey val id: String,
  val name: String,
  val protocol: String,
  val host: String,
  val port: Int,
  val isFavorite: Boolean,
  val createdAt: Long,
  val updatedAt: Long,
  val notes: String,
  val sshUsername: String,
  val sshAuthMethod: String,
  val upstreamProxyType: String,
  val upstreamProxyHost: String,
  val upstreamProxyPort: Int,
  val transport: String,
  val security: String,
  val sni: String,
  val alpn: String,
  val wsPath: String,
  val wsHost: String,
  val allowInsecure: Boolean,
  val fingerprint: String,
  val flow: String,
  val vmessAlterId: Int,
  val vmessCipher: String,
  val ssMethod: String,
  val socksUsername: String,
  val socksUdpEnabled: Boolean,
) {
  fun toDomain(): VpnProfile =
    VpnProfile(
      id = id,
      name = name,
      protocol = runCatching { ProtocolType.valueOf(protocol) }.getOrDefault(ProtocolType.VLESS),
      host = host,
      port = port,
      isFavorite = isFavorite,
      createdAt = createdAt,
      updatedAt = updatedAt,
      notes = notes,
      sshUsername = sshUsername,
      sshAuthMethod =
        runCatching { SshAuthMethod.valueOf(sshAuthMethod) }.getOrDefault(SshAuthMethod.PASSWORD),
      upstreamProxyType =
        runCatching { UpstreamProxyType.valueOf(upstreamProxyType) }
          .getOrDefault(UpstreamProxyType.NONE),
      upstreamProxyHost = upstreamProxyHost,
      upstreamProxyPort = upstreamProxyPort,
      transport = runCatching { TransportType.valueOf(transport) }.getOrDefault(TransportType.TCP),
      security = runCatching { SecurityType.valueOf(security) }.getOrDefault(SecurityType.NONE),
      sni = sni,
      alpn = alpn,
      wsPath = wsPath,
      wsHost = wsHost,
      allowInsecure = allowInsecure,
      fingerprint = fingerprint,
      flow = flow,
      vmessAlterId = vmessAlterId,
      vmessCipher = vmessCipher,
      ssMethod = ssMethod,
      socksUsername = socksUsername,
      socksUdpEnabled = socksUdpEnabled,
    )

  companion object {
    fun fromDomain(profile: VpnProfile): ProfileEntity =
      ProfileEntity(
        id = profile.id,
        name = profile.name,
        protocol = profile.protocol.name,
        host = profile.host,
        port = profile.port,
        isFavorite = profile.isFavorite,
        createdAt = profile.createdAt,
        updatedAt = profile.updatedAt,
        notes = profile.notes,
        sshUsername = profile.sshUsername,
        sshAuthMethod = profile.sshAuthMethod.name,
        upstreamProxyType = profile.upstreamProxyType.name,
        upstreamProxyHost = profile.upstreamProxyHost,
        upstreamProxyPort = profile.upstreamProxyPort,
        transport = profile.transport.name,
        security = profile.security.name,
        sni = profile.sni,
        alpn = profile.alpn,
        wsPath = profile.wsPath,
        wsHost = profile.wsHost,
        allowInsecure = profile.allowInsecure,
        fingerprint = profile.fingerprint,
        flow = profile.flow,
        vmessAlterId = profile.vmessAlterId,
        vmessCipher = profile.vmessCipher,
        ssMethod = profile.ssMethod,
        socksUsername = profile.socksUsername,
        socksUdpEnabled = profile.socksUdpEnabled,
      )
  }
}

@Entity(tableName = "adblock_rules")
data class AdBlockRuleEntity(
  @PrimaryKey val id: String,
  val domain: String,
  val category: String,
  val isEnabled: Boolean,
  val source: String,
) {
  fun toDomain(): AdBlockRule =
    AdBlockRule(
      id = id,
      domain = domain,
      category =
        runCatching { AdBlockCategory.valueOf(category) }
          .getOrDefault(AdBlockCategory.CUSTOM_BLOCK),
      isEnabled = isEnabled,
      source = source,
    )

  companion object {
    fun fromDomain(rule: AdBlockRule): AdBlockRuleEntity =
      AdBlockRuleEntity(
        id = rule.id,
        domain = rule.domain.trim().lowercase().trimEnd('.'),
        category = rule.category.name,
        isEnabled = rule.isEnabled,
        source = rule.source,
      )
  }
}

@Dao
interface ProfileDao {
  @Query("SELECT * FROM vpn_profiles ORDER BY isFavorite DESC, updatedAt DESC")
  fun observeAll(): Flow<List<ProfileEntity>>

  @Query("SELECT * FROM vpn_profiles WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): ProfileEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: ProfileEntity)

  @Query("DELETE FROM vpn_profiles WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("DELETE FROM vpn_profiles")
  suspend fun deleteAll()
}

@Dao
interface AdBlockDao {
  @Query("SELECT * FROM adblock_rules ORDER BY category ASC, domain ASC")
  fun observeAll(): Flow<List<AdBlockRuleEntity>>

  @Query("SELECT COUNT(*) FROM adblock_rules")
  suspend fun count(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(rules: List<AdBlockRuleEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(rule: AdBlockRuleEntity)

  @Query("DELETE FROM adblock_rules WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("DELETE FROM adblock_rules WHERE source = :source")
  suspend fun deleteBySource(source: String)

  @Query("DELETE FROM adblock_rules")
  suspend fun deleteAll()
}

@Database(
  entities = [ProfileEntity::class, AdBlockRuleEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun profileDao(): ProfileDao
  abstract fun adBlockDao(): AdBlockDao

  companion object {
    @Volatile private var INSTANCE: AppDatabase? = null

    fun getInstance(context: Context): AppDatabase =
      INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "gvpn_local.db",
              )
              .build()
              .also { INSTANCE = it }
        }
  }
}

fun Flow<List<ProfileEntity>>.mapProfiles(): Flow<List<VpnProfile>> = map { list ->
  list.map { it.toDomain() }
}

fun Flow<List<AdBlockRuleEntity>>.mapRules(): Flow<List<AdBlockRule>> = map { list ->
  list.map { it.toDomain() }
}
