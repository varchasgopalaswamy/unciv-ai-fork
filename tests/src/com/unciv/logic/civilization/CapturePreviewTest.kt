package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.battle.AttackParticipantOutcome
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Capture predictions share execution rules and must not mutate the inspected position. */
@RunWith(BaseTestRunner::class)
class CapturePreviewTest {
    private class Position(cityState: Boolean = false) {
        val game = TestGame().apply { makeHexagonalMap(8) }
        val player = game.addCiv(isPlayer = true, cityStateType = if (cityState) "Cultured" else null)
        val enemy = game.addCiv(isPlayer = true)
        val source = game.getTile(0, 0)
        val target = game.getTile(1, 0)
        val operations = PlayerCombatOperations(player)

        init {
            game.addUnit("Settler", player, game.getTile(-6, 0))
            game.addUnit("Settler", enemy, game.getTile(6, 0))
            game.gameInfo.currentPlayer = player.civID
            game.gameInfo.currentPlayerCiv = player
            player.diplomacyFunctions.makeCivilizationsMeet(enemy)
            player.getDiplomacyManager(enemy)!!.declareWar()
            for (tile in game.tileMap.values) tile.setExplored(player, true)
        }

        fun preview(unit: MapUnit): PlayerCombatOperations.AttackPreview {
            player.viewableTiles = game.tileMap.values.toSet()
            val before = json().toJson(game.gameInfo)
            val result = operations.attacks(unit).first { it.target === target && it.attackFrom === source }
            result.civilianOrCityCapturePossible()
            result.civilianCaptureOutcome()
            assertEquals(before, json().toJson(game.gameInfo))
            return result
        }
    }

    @Test
    fun `civilian previews agree with capture conversion recovery and destruction`() {
        for ((name, originalOwner) in listOf("Worker" to false, "Settler" to false, "Settler" to true, "Great Scientist" to false)) {
            val p = Position()
            val attacker = p.game.addUnit("Warrior", p.player, p.source)
            val civilian = p.game.addUnit(name, p.enemy, p.target)
            if (originalOwner) civilian.originalOwner = p.player.civID
            val destroyed = name == "Great Scientist"
            val preview = p.preview(attacker)
            assertEquals(!destroyed, preview.civilianOrCityCapturePossible())
            assertEquals(if (destroyed) AttackParticipantOutcome.Destroyed else AttackParticipantOutcome.Captured,
                preview.civilianCaptureOutcome())
            assertEquals(destroyed, BattleUnitCapture.destroysCivilianOnCapture(civilian, p.player))
            val result = p.operations.tryAttack(attacker, p.target, p.source)!!
            assertEquals(!destroyed, result.defenderCaptured)
            assertEquals(destroyed, result.defenderDestroyed)
            if (!destroyed) assertEquals(if (name == "Settler" && !originalOwner) "Worker" else name,
                p.player.units.getUnitById(civilian.id)!!.name)
        }
    }

    @Test
    fun `city states destroy settlers even when recovering their own`() {
        val p = Position(cityState = true)
        val attacker = p.game.addUnit("Warrior", p.player, p.source)
        val settler = p.game.addUnit("Settler", p.enemy, p.target).apply { originalOwner = p.player.civID }
        // City-states use native capture execution, not human-major player operations.
        val before = json().toJson(p.game.gameInfo)
        assertTrue(BattleUnitCapture.destroysCivilianOnCapture(settler, p.player))
        assertEquals(before, json().toJson(p.game.gameInfo))
        val outcome = BattleUnitCapture.captureCivilianUnit(MapUnitCombatant(attacker), MapUnitCombatant(settler))
        assertEquals(AttackParticipantOutcome.Destroyed, outcome)
        assertTrue(settler.isDestroyed)
    }

    @Test
    fun `ranged fire and attacks on a military guard do not promise direct civilian capture`() {
        val ranged = Position()
        val archer = ranged.game.addUnit("Archer", ranged.player, ranged.source)
        val worker = ranged.game.addUnit("Worker", ranged.enemy, ranged.target)
        assertFalse(ranged.preview(archer).civilianOrCityCapturePossible())
        assertNull(ranged.preview(archer).civilianCaptureOutcome())
        assertFalse(ranged.operations.tryAttack(archer, ranged.target, ranged.source)!!.defenderCaptured)
        assertSame(ranged.enemy, worker.civ)

        val guarded = Position()
        val warrior = guarded.game.addUnit("Warrior", guarded.player, guarded.source)
        val guard = guarded.game.addUnit("Warrior", guarded.enemy, guarded.target).apply { health = 1 }
        val civilian = guarded.game.addUnit("Worker", guarded.enemy, guarded.target)
        assertFalse(guarded.preview(warrior).civilianOrCityCapturePossible())
        assertNull(guarded.preview(warrior).civilianCaptureOutcome())
        guarded.operations.tryAttack(warrior, guarded.target, guarded.source)!!
        assertTrue(guard.isDestroyed)
        assertSame(guarded.player, civilian.civ) // Capture follows defeating the guard and entering its tile.
    }

    @Test
    fun `city previews and execution respect helicopter and ranged capture restrictions`() {
        for (name in listOf("Warrior", "Archer", "Helicopter Gunship")) {
            val p = Position()
            val city = p.game.addCity(p.enemy, p.target).apply { health = 1 }
            val attacker = p.game.addUnit(name, p.player, p.source)
            val preview = p.preview(attacker)
            assertEquals(name == "Warrior", preview.civilianOrCityCapturePossible())
            assertNull(preview.civilianCaptureOutcome())
            val result = p.operations.tryAttack(attacker, p.target, p.source)!!
            assertEquals(name == "Warrior", result.defenderCaptured)
            assertEquals(name == "Warrior", city.hasJustBeenConquered)
            assertEquals(name == "Warrior", p.player.popupAlerts.any { it.type == AlertType.CityConquered })
        }
    }

    @Test
    fun `healthy cities do not become capture opportunities until damage can defeat them`() {
        val p = Position()
        p.game.addCity(p.enemy, p.target)
        val warrior = p.game.addUnit("Warrior", p.player, p.source)
        assertFalse(p.preview(warrior).civilianOrCityCapturePossible())
    }

    @Test
    fun `city defeat actions preserve destruction and barbarian raids`() {
        val p = Position()
        p.game.addCity(p.enemy, p.game.getTile(5, 0))
        val city = p.game.addCity(p.enemy, p.target).apply { health = 1 }
        val destroyer = p.game.addDefaultMeleeUnitWithUniques(p.player, p.source, "Destroys [All] cities instead of capturing")
        assertEquals(Battle.CityDefeatAction.Destroy, Battle.cityDefeatAction(MapUnitCombatant(destroyer), city))
        assertFalse(p.preview(destroyer).civilianOrCityCapturePossible())
        p.operations.tryAttack(destroyer, p.target, p.source)!!
        assertFalse(city in p.enemy.cities)

        val raid = Position()
        val raidedCity = raid.game.addCity(raid.enemy, raid.target).apply { health = 1 }
        val barbarian = raid.game.addBarbarianCiv()
        val brute = raid.game.addUnit("Brute", barbarian, raid.source)
        raid.enemy.addGold(300)
        val gold = raid.enemy.gold
        assertEquals(Battle.CityDefeatAction.Raid, Battle.cityDefeatAction(MapUnitCombatant(brute), raidedCity))
        Battle.attack(MapUnitCombatant(brute), com.unciv.logic.battle.CityCombatant(raidedCity))
        assertTrue(brute.isDestroyed)
        assertSame(raid.enemy, raidedCity.civ)
        assertEquals(gold - 200, raid.enemy.gold)
    }
}
