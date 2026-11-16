package com.tvtime.app

import android.app.Activity
import android.app.AlertDialog
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse

/**
 * Shows the page's drop-down lists (<select>, e.g. season / episode / server pickers) as a
 * list the remote can move through. Everything else a page might pop up (alert, confirm,
 * prompt) is dismissed, which also silences ad scripts that spam alerts.
 */
class Prompts(private val activity: Activity) : GeckoSession.PromptDelegate {

    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse>? {
        val result = GeckoResult<PromptResponse>()
        var answered = false
        fun answer(r: PromptResponse) {
            if (!answered) {
                answered = true
                result.complete(r)
            }
        }
        val flat = ArrayList<ChoicePrompt.Choice>()
        fun collect(list: Array<ChoicePrompt.Choice>?) {
            list?.forEach { c ->
                val children = c.items
                when {
                    children != null -> collect(children)
                    !c.separator && !c.disabled -> flat.add(c)
                }
            }
        }
        collect(prompt.choices)
        if (flat.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())

        val labels: Array<CharSequence> = flat.map { it.label as CharSequence }.toTypedArray()
        val builder = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
        prompt.title?.takeIf { it.isNotBlank() }?.let { builder.setTitle(it) }

        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            val checked = flat.map { it.selected }.toBooleanArray()
            builder.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            builder.setPositiveButton("OK") { _, _ ->
                answer(prompt.confirm(flat.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
            }
        } else {
            val current = flat.indexOfFirst { it.selected }
            builder.setSingleChoiceItems(labels, current) { dialog, which ->
                answer(prompt.confirm(flat[which]))
                dialog.dismiss()
            }
        }
        builder.setOnDismissListener { answer(prompt.dismiss()) }
        activity.runOnUiThread { builder.show() }
        return result
    }
}
