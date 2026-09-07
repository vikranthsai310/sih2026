package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Board 01 — cold start. `docs/REDESIGN.md` task 6.1.
 *
 * ## Why a splash exists at all, on a product that hates ceremony
 *
 * The ASR graph is 130–190 MB and takes one to two seconds to preload. Risk **T-11** says
 * that cost must never be paid on a key press — and before this screen the application went
 * straight to the operating screen with the transmit control live and nothing loaded behind
 * it. An operator who pressed it in that window got silence and no explanation. So this is
 * not branding; it is the interval made visible, with the one fact that matters on it: how
 * far along the load is.
 *
 * ## The hero is a placeholder, deliberately
 *
 * The canvas fills the top of this board with a photograph through its drop-in image slot.
 * A photograph is not shipped here, and the reason is structural rather than aesthetic: the
 * application declares no `INTERNET` permission — constraint **C2**, asserted in CI — so it
 * has no path to fetch one, and a bundled photograph is megabytes against an installer that
 * constraint **N2** caps at thirty. The band is drawn instead, and it is drawn as a *field*
 * rather than as a fake photo, so nobody mistakes it for an asset that failed to load. Drop
 * `assets/splash-flood.png` into `res/drawable` and replace [HeroBand] when the budget for
 * it exists.
 *
 * ## The three figures
 *
 * `52 B`, `10`, `1600×` are the product's whole argument, and a cold start is the one moment
 * an operator is looking at the screen with nothing to do. They are Sky because every figure
 * in this application is Sky.
 */
@Composable
fun SplashScreen(
    /** What is loading, in the language being loaded. Null before anything is known. */
    loading: String?,
    /** 0..1, or null when the loader cannot say — which draws an indeterminate band. */
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val p = palette
    Column(
        modifier
            .fillMaxSize()
            .background(p.paper)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription =
                    loading?.let { "Starting. $it" } ?: "Starting. Loading the speech models."
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeroBand()

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Text(
                "iTantra",
                fontSize = Tokens.Display,
                fontWeight = FontWeight.Bold,
                color = p.ink,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Speech in. Speech out. No network.",
                fontSize = Tokens.Label,
                color = p.muted,
            )

            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(p.periwinkle.mid, RoundedCornerShape(Tokens.RadiusPill)),
                ) {
                    // An unknown fraction is drawn as a breathing full band rather than as a
                    // bar stopped at nought: a progress bar at zero reads as stuck, and this
                    // one is never stuck — it is loading something large.
                    Box(
                        Modifier
                            .fillMaxWidth(progress?.coerceIn(0f, 1f) ?: 1f)
                            .height(6.dp)
                            .then(if (progress == null) Modifier.alpha(pulseAlpha()) else Modifier)
                            .background(p.periwinkle.core, RoundedCornerShape(Tokens.RadiusPill)),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        loading ?: "Loading the speech models",
                        fontSize = Tokens.Instrument,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        lineHeight = Tokens.Instrument * Tokens.INDIC_LINE_HEIGHT,
                        color = p.muted,
                    )
                    progress?.let {
                        Text(
                            "${(it * 100).toInt()} %",
                            fontSize = Tokens.Instrument,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = p.periwinkle.deep,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Claim("52 B", "per sentence", Modifier.weight(1f))
                Claim("10", "languages", Modifier.weight(1f))
                Claim("1600×", "under raw audio", Modifier.weight(1f))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.LockOpen, contentDescription = null, tint = p.sky.core, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                "100 % offline · no SIM · no cloud",
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = p.sky.deep,
            )
        }
    }
}

/**
 * The hero band, standing in for the photograph the canvas drops in.
 *
 * Drawn as a graded field with the aeroplane-mode chip the board carries, fading into Paper
 * so the wordmark below it sits on the ground rather than on an edge. It is deliberately not
 * a fake photograph: an obviously abstract band reads as a decision, where a grey rectangle
 * with a picture icon reads as a failure.
 */
@Composable
private fun HeroBand() {
    val p = palette
    Box(
        Modifier
            .fillMaxWidth()
            .height(400.dp)
            .background(
                Brush.verticalGradient(
                    0f to p.periwinkle.deep,
                    0.55f to p.periwinkle.core,
                    0.82f to p.periwinkle.tint,
                    1f to p.paper,
                ),
            ),
    ) {
        Row(
            Modifier
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(Tokens.RadiusPill))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(pulseAlpha())
                    .background(p.mint.core, CircleShape),
            )
            Text(
                "AEROPLANE MODE · NO SIM",
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
    }
}

/** One of the three claims. Sky, because every figure in this application is Sky. */
@Composable
private fun Claim(
    figure: String,
    label: String,
    modifier: Modifier,
) {
    val p = palette
    Column(
        modifier
            .background(p.sky.tint, RoundedCornerShape(Tokens.RadiusControl))
            .padding(horizontal = 12.dp, vertical = 11.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$figure $label" },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            figure,
            fontSize = Tokens.Callout,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = p.sky.deep,
        )
        Text(label, fontSize = Tokens.Instrument, lineHeight = Tokens.Instrument * 1.25f, color = p.muted)
    }
}
