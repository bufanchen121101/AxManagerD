package dev.frb.demo_axerish

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import dev.frb.demo_axerish.ui.theme.AxeronAPITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AxeronAPITheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StreamingTerminal("env", modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun StreamingTerminal(
    cmd: String,
    modifier: Modifier = Modifier
) {
    val lines = remember { mutableStateListOf<String>() }

    LaunchedEffect(cmd) {
        withContext(Dispatchers.IO) {
            Shell.cmd(cmd).to(lines).exec()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        items(lines) { line ->
            Text(
                text = line,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}



