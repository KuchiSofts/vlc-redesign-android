package org.videolan.vlc.gui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.videolan.resources.AppContextProvider
import org.videolan.tools.KEY_SET_LOCALE
import org.videolan.tools.LocaleUtils
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.RdDropdown
import org.videolan.vlc.util.RdDeviceLocale
import org.videolan.vlc.util.RdLocaleChoices

/**
 * VLC Redesign: WelcomeStep.qml: the logo, "Welcome to VLC!", the text; and the interface language, chosen here
 * on Android (the desktop's installer asks it): the onboarding is drawn again in it at once.
 */
class OnboardingWelcomeFragment : OnboardingFragment() {
    private lateinit var titleView: TextView

    override fun getDefaultViewForTalkback() = titleView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.onboarding_welcome, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleView = view.findViewById(R.id.welcome_title)
        val ctx = requireContext()
        val settings = Settings.getInstance(ctx)
        val locales = LocaleUtils.getLocalesUsedInProject(BuildConfig.TRANSLATION_ARRAY, getString(R.string.device_default))
        // the shared list (RdLocaleChoices, RdDropdown): Device default, then the languages this device is set to,
        // marked "On this device", then the others from A to Z; it scrolls inside the window
        val values = locales.localeEntryValues.toList()
        val names = locales.localeEntries.toList()
        val choices = RdLocaleChoices.order(listOf(RdLocaleChoices.Choice(values[0], names[0])), (1 until values.size).map { values[it] to names[it] },
                RdLocaleChoices.detectedLanguages(values.drop(1), RdDeviceLocale.languageTags()))
        val current = settings.getString(KEY_SET_LOCALE, "") ?: ""
        val index = choices.indexOfFirst { it.value == current }.takeIf { it >= 0 } ?: 0
        val value = view.findViewById<TextView>(R.id.language_value)
        value.text = choices[index].label
        val well = view.findViewById<View>(R.id.language_well)
        well.contentDescription = getString(R.string.rd_interface_language) + ", " + choices[index].label
        well.setOnClickListener {
            RdDropdown.show(well, RdDropdown.rows(choices, current), RdLocaleChoices.openAt(choices, current, preferDetected = current.isEmpty())) { position ->
                val chosen = choices[position].value
                if (chosen != current) {
                    settings.putSingle(KEY_SET_LOCALE, chosen)
                    AppContextProvider.setLocale(chosen)
                    requireActivity().recreate()
                }
            }
        }
    }

    companion object {
        fun newInstance(): OnboardingWelcomeFragment {
            return OnboardingWelcomeFragment()
        }
    }
}
