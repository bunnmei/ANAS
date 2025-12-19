package space.webkombinat.anas.presentation

import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.webkombinat.anas.R
import space.webkombinat.anas.data.PreviewSize
import space.webkombinat.anas.data.UITheme
import space.webkombinat.anas.presentation.components.ClickableCheckBoxPanel


@Composable
fun SettingScreen(
    modifier: Modifier = Modifier,
    vm: SettingVM
) {
    val theme by vm.theme.collectAsState(UITheme.SYSTEM.theme)
    SettingScreen(
        theme = theme,
        colorThemeClick = {
            vm.setTheme(it)
        }
    )

}

@Composable
private fun SettingScreen(
    modifier: Modifier = Modifier,
    theme: Int,
    colorThemeClick: (Int) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = modifier
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(R.string.theme),
                fontSize = 12.sp
            )
            Spacer(modifier = modifier.weight(1f))
        }
        HorizontalDivider(
            modifier.padding(horizontal = 16.dp)
        )

        ClickableCheckBoxPanel(
            text = stringResource(R.string.system),
            checked = theme == UITheme.SYSTEM.theme,
        ) {
            colorThemeClick(UITheme.SYSTEM.theme)
        }

        ClickableCheckBoxPanel(
            text = stringResource(R.string.dark),
            checked = theme == UITheme.DARK.theme,
        ) {
            colorThemeClick(UITheme.DARK.theme)
        }

        ClickableCheckBoxPanel(
            text = stringResource(R.string.light),
            checked = theme == UITheme.LIGHT.theme,
        ) {
            colorThemeClick(UITheme.LIGHT.theme)
        }

        Spacer(modifier = modifier.weight(1f))
    }
}

@Preview(
    showBackground = true,
    widthDp = PreviewSize.WIDTH,
    heightDp = PreviewSize.HEIGHT,
)
@Composable
private fun SettingScreenPreview() {
    SettingScreen(
        theme = UITheme.SYSTEM.theme,
        colorThemeClick = {}
    )
}

