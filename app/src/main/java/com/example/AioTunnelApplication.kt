package com.example

import android.app.Application
import com.example.core.connection.ConnectionManager
import com.example.core.logger.AppLogger
import com.example.core.stats.StatisticsManager
import com.example.data.database.AioDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.security.KeystoreCryptoManager
import com.example.data.security.SshHostKeyStore
import com.example.domain.repositories.ProfileRepository
import com.example.domain.repositories.SettingsRepository
import com.example.engines.adapter.EngineFactory

class AioTunnelApplication : Application() {
    lateinit var database: AioDatabase
        private set
    lateinit var cryptoManager: KeystoreCryptoManager
        private set
    lateinit var sshHostKeyStore: SshHostKeyStore
        private set
    lateinit var profileRepository: ProfileRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var statisticsManager: StatisticsManager
        private set
    lateinit var engineFactory: EngineFactory
        private set
    lateinit var connectionManager: ConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = AioDatabase.getInstance(this)
        cryptoManager = KeystoreCryptoManager(this)
        sshHostKeyStore = SshHostKeyStore(this)
        profileRepository = ProfileRepository(database.tunnelProfileDao(), cryptoManager)
        settingsRepository = SettingsRepository(SettingsDataStore(this))
        statisticsManager = StatisticsManager()
        engineFactory = EngineFactory(sshHostKeyStore)
        connectionManager = ConnectionManager(this)

        AppLogger.info("AioApp", "AIO Tunnel VPN inicializado (Keystore AES-GCM=${cryptoManager.isKeystoreHardwareAvailable()})")
    }
}
