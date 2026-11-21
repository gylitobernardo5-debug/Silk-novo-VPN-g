package com.example.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.model.AdBlockCategory
import com.example.model.AdBlockRule
import com.example.model.AppSettings
import com.example.model.InstalledAppInfo
import com.example.model.ProfileSecrets
import com.example.model.VpnProfile
import com.example.parser.BatchImportResult
import com.example.parser.ProfileParser
import com.example.security.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

class VpnRepository(context: Context) {
  private val appContext = context.applicationContext
  private val db = AppDatabase.getInstance(appContext)
  private val profileDao = db.profileDao()
  private val adBlockDao = db.adBlockDao()
  private val vault = CredentialVault(appContext)
  private val prefs = SettingsDataStore(appContext)

  private val httpClient =
    OkHttpClient.Builder()
      .connectTimeout(12, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .build()

  val profilesFlow: Flow<List<VpnProfile>> = profileDao.observeAll().mapProfiles()
  val adBlockRulesFlow: Flow<List<AdBlockRule>> = adBlockDao.observeAll().mapRules()
  val settingsFlow: Flow<AppSettings> = prefs.settingsFlow
  val activeProfileIdFlow: Flow<String?> = prefs.activeProfileIdFlow

  suspend fun ensureSeededBlocklists() {
    if (adBlockDao.count() == 0) {
      adBlockDao.upsertAll(DefaultAdBlockLists.buildDefaultRules().map(AdBlockRuleEntity::fromDomain))
    }
  }

  suspend fun getProfileWithSecrets(id: String): Pair<VpnProfile, ProfileSecrets>? {
    val entity = profileDao.getById(id) ?: return null
    return entity.toDomain() to vault.getSecrets(id)
  }

  suspend fun getSecrets(profileId: String): ProfileSecrets =
    withContext(Dispatchers.IO) { vault.getSecrets(profileId) }

  suspend fun saveProfile(profile: VpnProfile, secrets: ProfileSecrets, selectAsActive: Boolean = false) {
    val updated = profile.copy(updatedAt = System.currentTimeMillis())
    withContext(Dispatchers.IO) {
      vault.saveSecrets(updated.id, secrets)
      profileDao.upsert(ProfileEntity.fromDomain(updated))
      if (selectAsActive || activeProfileIdFlow.first() == null) {
        prefs.setActiveProfileId(updated.id)
      }
    }
  }

  suspend fun duplicateProfile(id: String): VpnProfile? = withContext(Dispatchers.IO) {
    val existing = profileDao.getById(id)?.toDomain() ?: return@withContext null
    val secrets = vault.getSecrets(id)
    val copy =
      existing.copy(
        id = UUID.randomUUID().toString(),
        name = "${existing.displayTitle()} (Copy)",
        isFavorite = false,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
      )
    vault.saveSecrets(copy.id, secrets)
    profileDao.upsert(ProfileEntity.fromDomain(copy))
    copy
  }

  suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
    val entity = profileDao.getById(id) ?: return@withContext
    profileDao.upsert(entity.copy(isFavorite = !entity.isFavorite))
  }

  suspend fun deleteProfile(id: String) = withContext(Dispatchers.IO) {
    profileDao.deleteById(id)
    vault.deleteSecrets(id)
    if (activeProfileIdFlow.first() == id) {
      val next = profileDao.observeAll().first().firstOrNull()?.id
      prefs.setActiveProfileId(next)
    }
  }

  suspend fun setActiveProfileId(id: String?) {
    prefs.setActiveProfileId(id)
  }

  suspend fun importFromText(rawText: String, activateFirst: Boolean = true): BatchImportResult =
    withContext(Dispatchers.IO) {
      val result = ProfileParser.parseBatch(rawText)
      result.imported.forEachIndexed { index, parsed ->
        vault.saveSecrets(parsed.profile.id, parsed.secrets)
        profileDao.upsert(ProfileEntity.fromDomain(parsed.profile))
        if (index == 0 && activateFirst) {
          prefs.setActiveProfileId(parsed.profile.id)
        }
      }
      result
    }

  suspend fun exportProfileUri(profile: VpnProfile): String = withContext(Dispatchers.IO) {
    val secrets = vault.getSecrets(profile.id)
    ProfileParser.exportAsUri(profile, secrets)
  }

  suspend fun exportProfileJson(profile: VpnProfile, includeSecrets: Boolean): String =
    withContext(Dispatchers.IO) {
      val secrets = vault.getSecrets(profile.id)
      ProfileParser.exportAsJson(profile, secrets, includeSecrets)
    }

  suspend fun addDomainRule(domain: String, category: AdBlockCategory) = withContext(Dispatchers.IO) {
    val clean = domain.trim().lowercase().removePrefix("http://").removePrefix("https://")
      .substringBefore("/").trimEnd('.')
    if (clean.isBlank() || !clean.contains(".")) return@withContext
    val rule =
      AdBlockRule(
        id = "${category.name.lowercase()}_$clean",
        domain = clean,
        category = category,
        isEnabled = true,
        source = "user",
      )
    adBlockDao.upsert(AdBlockRuleEntity.fromDomain(rule))
  }

  suspend fun toggleDomainRule(rule: AdBlockRule) = withContext(Dispatchers.IO) {
    adBlockDao.upsert(AdBlockRuleEntity.fromDomain(rule.copy(isEnabled = !rule.isEnabled)))
  }

  suspend fun deleteDomainRule(id: String) = withContext(Dispatchers.IO) {
    adBlockDao.deleteById(id)
  }

  suspend fun updateRemoteBlocklist(): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
      val req = Request.Builder().url(DefaultAdBlockLists.REMOTE_HOSTS_URL).build()
      val body =
        httpClient.newCall(req).execute().use { resp ->
          check(resp.isSuccessful) { "HTTP ${resp.code}" }
          resp.body?.string().orEmpty()
        }
      val parsedDomains =
        body
          .lineSequence()
          .map { it.substringBefore('#').trim() }
          .filter { it.isNotEmpty() }
          .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2 && (parts[0] == "127.0.0.1" || parts[0] == "0.0.0.0")) {
              val d = parts[1].lowercase().trimEnd('.')
              if (d != "localhost" && d != "localhost.localdomain" && d.contains('.')) d else null
            } else null
          }
          .distinct()
          .take(1500) // Cap memory footprint to keep RAM low and DNS lookups sub-millisecond
          .toList()

      require(parsedDomains.isNotEmpty()) { "Empty hosts response" }

      adBlockDao.deleteBySource("remote")
      val entities =
        parsedDomains.map { domain ->
          AdBlockRuleEntity(
            id = "remote_$domain",
            domain = domain,
            category = AdBlockCategory.ADS.name,
            isEnabled = true,
            source = "remote",
          )
        }
      adBlockDao.upsertAll(entities)
      prefs.updateSettings { it.copy(lastBlocklistUpdateMs = System.currentTimeMillis()) }
      entities.size
    }
  }

  suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
    prefs.updateSettings(transform)
  }

  suspend fun wipeAllProfilesAndCredentials() = withContext(Dispatchers.IO) {
    profileDao.deleteAll()
    adBlockDao.deleteBySource("user")
    vault.wipeAll()
    prefs.setActiveProfileId(null)
  }

  suspend fun loadLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
    val pm = appContext.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveInfos = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
    val seen = HashSet<String>()
    resolveInfos
      .mapNotNull { info ->
        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
        if (pkg == appContext.packageName || !seen.add(pkg)) return@mapNotNull null
        val appInfo = info.activityInfo.applicationInfo
        val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = info.loadLabel(pm).toString().ifBlank { pkg }
        InstalledAppInfo(packageName = pkg, label = label, isSystemApp = isSys)
      }
      .sortedBy { it.label.lowercase() }
  }
}
