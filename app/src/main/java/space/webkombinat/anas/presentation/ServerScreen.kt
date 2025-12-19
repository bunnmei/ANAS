package space.webkombinat.anas.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import space.webkombinat.anas.data.PreviewSize

@Composable
fun ServerScreen(
    modifier: Modifier = Modifier,
    vm: ServerVM
) {
    ServerScreen()
}

@Composable
private fun ServerScreen(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Bottom
    ) {
        Button(
            onClick = {}
        ) {
            Row {
                Text("ドライブフォルダを選択")
                Spacer(modifier = modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null
                )
            }
        }

        Button(
            onClick = {},
            enabled = true
        ) {
            Row {
                Text("サーバースタート")
                Spacer(modifier = modifier.width(10.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                    contentDescription = null
                )
            }
        }
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
