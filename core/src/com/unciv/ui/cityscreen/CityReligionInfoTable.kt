package com.unciv.ui.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.city.CityInfoReligionManager
import com.unciv.models.Religion
import com.unciv.ui.civilopedia.CivilopediaCategories
import com.unciv.ui.civilopedia.CivilopediaScreen
import com.unciv.ui.images.IconCircleGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.utils.BaseScreen
import com.unciv.ui.utils.ExpanderTab
import com.unciv.ui.utils.extensions.addSeparator
import com.unciv.ui.utils.extensions.addSeparatorVertical
import com.unciv.ui.utils.extensions.onClick
import com.unciv.ui.utils.extensions.toLabel

class CityReligionInfoTable(
    private val religionManager: CityInfoReligionManager,
    showMajority: Boolean = false
) : Table(BaseScreen.skin) {
    private val civInfo = religionManager.cityInfo.civInfo
    private val gameInfo = civInfo.gameInfo

    init {
        val gridColor = Color.DARK_GRAY
        val followers = religionManager.getNumberOfFollowers()
        val futurePressures = religionManager.getPressuresFromSurroundingCities()

        if (showMajority) {
            val majorityReligion = religionManager.getMajorityReligion()
            val (iconName, label) = getIconAndLabel(majorityReligion)
            add(linkedReligionIcon(iconName, majorityReligion?.name)).pad(5f)
            add()  // skip vertical separator
            add("Majority Religion: [$label]".toLabel()).colspan(3).center().row()
        }

        if (religionManager.religionThisIsTheHolyCityOf != null) {
            val (iconName, label) = getIconAndLabel(religionManager.religionThisIsTheHolyCityOf)
            add(linkedReligionIcon(iconName, religionManager.religionThisIsTheHolyCityOf)).pad(5f)
            add()
            add("Holy city of: [$label]".toLabel()).colspan(3).center().row()
        }

        if (!followers.isEmpty()) {
            add().pad(5f)  // column for icon
            addSeparatorVertical(gridColor)
            add("Followers".toLabel()).pad(5f)
            addSeparatorVertical(gridColor)
            add("Pressure".toLabel()).pad(5f).row()
            addSeparator(gridColor)

            for ((religion, followerCount) in followers) {
                val iconName = gameInfo.religions[religion]!!.getIconName()
                add(linkedReligionIcon(iconName, religion)).pad(5f)
                addSeparatorVertical(gridColor)
                add(followerCount.toLabel()).pad(5f)
                addSeparatorVertical(gridColor)
                if (futurePressures.containsKey(religion))
                    add(("+ [${futurePressures[religion]!!}] pressure").toLabel()).pad(5f)
                else
                    add()
                row()
            }
        }
    }

    private fun getIconAndLabel(religionName: String?) =
        getIconAndLabel(gameInfo.religions[religionName])
    private fun getIconAndLabel(religion: Religion?): Pair<String, String> {
        return if (religion == null) "Religion" to "None"
            else religion.getIconName() to religion.getReligionDisplayName()
    }
    private fun linkedReligionIcon(iconName: String, religion: String?): IconCircleGroup {
        val icon = ImageGetter.getCircledReligionIcon(iconName, 30f)
        if (religion == null) return icon
        icon.onClick {
            val newScreen = if (religion == iconName)
                EmpireOverviewScreen(civInfo, EmpireOverviewCategories.Religion.name, religion)
            else CivilopediaScreen(gameInfo.ruleSet, UncivGame.Current.screen!!, CivilopediaCategories.Belief, religion )
            UncivGame.Current.setScreen(newScreen)
        }
        return icon
    }

    fun asExpander(onChange: (()->Unit)?): ExpanderTab {
        val (icon, label) = getIconAndLabel(religionManager.getMajorityReligion())
        return ExpanderTab(
                title = "Majority Religion: [$label]",
                fontSize = Constants.defaultFontSize,
                icon = ImageGetter.getCircledReligionIcon(icon, 30f),
                defaultPad = 0f,
                persistenceID = "CityStatsTable.Religion",
                startsOutOpened = false,
                onChange = onChange
            ) {
                defaults().center().pad(5f)
                it.add(this)
            }
    }
}
