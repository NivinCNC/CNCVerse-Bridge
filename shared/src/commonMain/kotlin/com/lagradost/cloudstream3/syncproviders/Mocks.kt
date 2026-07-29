package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.SimklApi

open class AuthAPI
open class SyncAPI : AuthAPI()
open class AuthRepo(open val api: AuthAPI)
open class SyncRepo(override val api: SyncAPI) : AuthRepo(api)
open class AccountManager {
    companion object {
        val simklApi = SimklApi()
        val aniListApi = com.lagradost.cloudstream3.syncproviders.providers.AniListApi()
        val malApi = com.lagradost.cloudstream3.syncproviders.providers.MalApi()
        val kitsuApi = com.lagradost.cloudstream3.syncproviders.providers.KitsuApi()
    }
}