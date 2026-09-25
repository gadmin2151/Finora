package work.gadmin.finora.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.User

@Composable
fun ProfileAvatar(user: User?, vm: FinoraViewModel, size: Dp = 44.dp) {
    var bitmap by remember(user?.id, user?.avatar_url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(user?.id, user?.avatar_url) { mutableStateOf(false) }
    LaunchedEffect(user?.id, user?.avatar_url) {
        if (user?.avatar_url != null) {
            try {
                bitmap =
                    withContext(Dispatchers.IO) {
                        vm.avatarPhoto()?.let {
                            BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            } // The account remains usable if its optional image is unavailable.
        }
    }
    Surface(color = SoftGreen, shape = CircleShape, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            bitmap?.let {
                Image(it, "Фото профиля", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
                ?: Text(
                    user?.name?.take(1)?.uppercase() ?: "F",
                    color = Mint,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier.semantics {
                            if (failed) contentDescription = "Фото профиля временно недоступно"
                        },
                )
        }
    }
}
