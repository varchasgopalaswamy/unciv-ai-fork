package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.MapSaver
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.testing.BaseTestRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(BaseTestRunner::class)
class GameStartingPositionSeedTest {
    private val directory = Files.createTempDirectory("starting-position-seed-").toFile()

    @Before
    fun setUp() {
        UncivGame.Current = UncivGame().apply {
            files = UncivFiles(Gdx.files, directory.absolutePath)
            settings = GameSettings()
        }
        RulesetCache.loadRulesets(noMods = true)
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun create(seed: Long?, mapSeed: Long = 422L, mapType: String = MapType.pangaea,
                       customize: (GameParameters) -> Unit = {}): GameInfo {
        val parameters = GameParameters().apply {
            baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            startingPositionSeed = seed
            numberOfCityStates = 4
            players = arrayListOf(
                Player("Rome", PlayerType.Human), Player("Greece"), Player("France"), Player("Egypt")
            )
        }
        customize(parameters)
        val map = MapParameters().apply {
            baseRuleset = parameters.baseRuleset
            this.seed = mapSeed
            type = mapType
            mapSize = MapSize.Tiny
        }
        return GameStarter.startNewGame(GameSetupInfo(parameters, map))
    }

    private fun units(game: GameInfo) = game.civilizations.associate { civ ->
        civ.civID to civ.units.getCivUnits().map { "${it.name}:${it.getTile().position}" }.sorted().toList()
    }

    private fun tiles(game: GameInfo) = game.tileMap.values.map {
        "${it.position}:${it.baseTerrain}:${it.terrainFeatures}:${it.naturalWonder}:" +
            "${it.resource}:${it.resourceAmount}:${it.improvement}:" +
            "${it.hasBottomRiver}:${it.hasBottomLeftRiver}:${it.hasBottomRightRiver}"
    }

    @Test
    fun `same seeds reproduce generated positions and resources with distinct game identities`() {
        for (mapType in listOf(MapType.pangaea, MapType.archipelago)) {
            val first = create(17L, mapType = mapType)
            repeat(2) {
                val second = create(17L, mapType = mapType)
                assertNotEquals(first.gameId, second.gameId)
                assertEquals("Starting units on $mapType", units(first), units(second))
                assertEquals("Tiles and resources on $mapType", tiles(first), tiles(second))
            }
        }
    }

    @Test
    fun `starting position seed changes placement independently of the terrain seed`() {
        val games = listOf(17L, 18L, 19L).map { create(it) }
        assertEquals(setOf(422L), games.map { it.tileMap.mapParameters.seed }.toSet())
        assertTrue(games.map(::units).distinct().size > 1)
    }

    @Test
    fun `random nation selection and player order use the placement seed`() {
        fun randomGame() = create(0L) {
            it.players = arrayListOf(Player(Constants.random, PlayerType.Human), Player(), Player(), Player())
            it.shufflePlayerOrder = true
        }
        val first = randomGame()
        val second = randomGame()
        assertEquals(first.civilizations.map { it.civID }, second.civilizations.map { it.civID })
        assertEquals(units(first), units(second))
        assertEquals(tiles(first), tiles(second))
    }

    @Test
    fun `saved maps without marked starts use reproducible fallback placement`() {
        val original = create(17L)
        for (tile in original.tileMap.values) {
            tile.militaryUnit = null
            tile.civilianUnit = null
            tile.airUnits.clear()
        }
        MapSaver.saveMap("placement-test", original.tileMap)
        fun fromMap(): GameInfo {
            val setup = GameSetupInfo(original).apply {
                mapParameters.name = "placement-test"
                mapFile = UncivGame.Current.files.getLocalFile("maps/placement-test")
            }
            return GameStarter.startNewGame(setup)
        }
        assertEquals(units(fromMap()), units(fromMap()))
    }

    @Test
    fun `seed survives parameter clones save serialization and cloned setup`() {
        for (seed in listOf(0L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val parameters = GameParameters().apply { startingPositionSeed = seed }
            assertEquals(seed, parameters.clone().startingPositionSeed)
            assertEquals(seed, json().fromJson(GameParameters::class.java, json().toJson(parameters)).startingPositionSeed)
            assertEquals(seed, GameSetupInfo(GameSetupInfo(parameters)).gameParameters.startingPositionSeed)
        }
        assertNull(GameParameters().startingPositionSeed)
    }

    @Test
    fun `setup seed does not replace random game identity or ordinary turn randomness`() {
        val game = create(null)
        val state = GameContext(gameInfo = game)
        assertEquals(state.stateBasedRandom("test").nextLong(), state.gameSetupRandom("test").nextLong())
        val turnRandom = state.stateBasedRandom("test").nextLong()
        game.gameParameters.startingPositionSeed = 123L
        assertEquals(turnRandom, state.stateBasedRandom("test").nextLong())
        assertNotEquals(turnRandom, state.gameSetupRandom("test").nextLong())
    }
}
