package space.webkombinat.anas.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import space.webkombinat.anas.data.PreviewSize

@Composable
fun ServerScreen(
    modifier: Modifier = Modifier,
    vm: ServerVM
) {
    ServerScreen()
}

@Composable
private fun ServerScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Server Screen")
    }
}

@Preview(
    showBackground = true,
    widthDp = PreviewSize.WIDTH,
    heightDp = PreviewSize.HEIGHT,
)
@Composable
fun ServerScreenPreview() {
    ServerScreen()
}
