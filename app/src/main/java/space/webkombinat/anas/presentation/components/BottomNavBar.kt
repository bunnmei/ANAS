package space.webkombinat.anas.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import space.webkombinat.anas.data.BottomNavItems
import space.webkombinat.anas.data.PreviewSize

@Composable
fun BottomNavBar(
    modifier: Modifier = Modifier,
    items: List<BottomNavItems> = listOf(
        BottomNavItems.Server,
        BottomNavItems.Setting
    ),
    onItemClick: (BottomNavItems) -> Unit = {}
) {
    var selectedItem by rememberSaveable {
        mutableStateOf(0)
    }
    NavigationBar(
        modifier = modifier.height(60.dp)
    ) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selectedItem == index,
                onClick = {
                    selectedItem = index
                    onItemClick(item)
                },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = "${item.route} route"
                    )
                }
            )
        }
    }
}

@Preview(
    showBackground = true,
    widthDp = PreviewSize.WIDTH,
    heightDp = PreviewSize.HEIGHT,
)
@Composable
fun BottomNavBarPreview(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        Spacer(modifier = modifier.weight(1f))
        BottomNavBar()
    }
}

