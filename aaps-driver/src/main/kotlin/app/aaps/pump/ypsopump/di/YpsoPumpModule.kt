package app.aaps.pump.ypsopump.di

import app.aaps.pump.ypsopump.YpsoPumpPlugin
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.crypto.KeyExchange
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
@Suppress("unused")
abstract class YpsoPumpModule {

    // All components use constructor injection via @Inject @Singleton,
    // so no explicit @Provides or @Binds needed for:
    //   - YpsoPumpPlugin
    //   - YpsoBleManager
    //   - SessionCrypto
    //   - KeyExchange
    //   - YpsoPumpState

    // Fragment injection for YpsoPumpFragment would go here:
    // @ContributesAndroidInjector abstract fun contributesYpsoPumpFragment(): YpsoPumpFragment
}
