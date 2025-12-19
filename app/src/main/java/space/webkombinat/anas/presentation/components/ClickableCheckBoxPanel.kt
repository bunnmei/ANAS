package space.webkombinat.anas.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.webkombinat.anas.data.PreviewSize

@Composable
fun ClickableCheckBoxPanel(
    modifier: Modifier = Modifier,
    text: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(70.dp),
        onClick = {onCheckedChange()},
    ) {
        Row(
            modifier = modifier
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                fontSize = 20.sp
            )
            Spacer(modifier = modifier.weight(1f))
            Checkbox(checked = checked, onCheckedChange = null)
        }
    }
}

@Preview(
    showBackground = true,
    heightDp = PreviewSize.HEIGHT,
    widthDp = PreviewSize.WIDTH,
)
@Composable
fun ClickableCheckBoxPanelPreview() {
    ClickableCheckBoxPanel(
        text = "ダークモード",
        checked = true,
        onCheckedChange = {}
    )
}
