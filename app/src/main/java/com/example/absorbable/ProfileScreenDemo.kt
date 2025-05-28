package com.example.absorbable

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.absorbable.R
import com.example.absorbable.attraction.compose.MagneticLayout
import com.example.magnetic.compose.rememberMagneticController




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreenDemo() {

    val controller = rememberMagneticController()

    MagneticLayout(
        modifier = Modifier.fillMaxSize(),
        controller = controller,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Spacer(modifier = Modifier.height(100.dp))


                Image(
                    painter = painterResource(R.drawable.me),
                    contentDescription = "Profile Picture",
                    modifier = Modifier
                        .size(180.dp)
                        .clip(CircleShape)
                        .attractable("ProfilePic"),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.height(16.dp))


                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Mahmoud Elsayed", fontSize = 24.sp, fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "@meo_essa", fontSize = 16.sp, color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { /* Change profile photo action */ },

                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Call,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Edit Profile Photo")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))


                SettingsSection(
                    items = listOf(
                        SettingsItem(
                            icon = Icons.Outlined.AccountCircle,
                            title = "My Profile",
                            iconTint = Color(0xFFEA4335)
                        ), SettingsItem(
                            icon = Icons.Outlined.FavoriteBorder,
                            title = "Saved Messages",
                            iconTint = Color(0xFF4285F4)
                        ), SettingsItem(
                            icon = Icons.Outlined.Call,
                            title = "Recent Calls",
                            iconTint = Color(0xFF34A853)
                        ), SettingsItem(
                            icon = Icons.Outlined.Delete,
                            title = "Devices", iconTint = Color(0xFFFBBC05)
                        ), SettingsItem(
                            icon = Icons.Outlined.FavoriteBorder,
                            title = "Chat Folders",
                            iconTint = Color(0xFF00ACC1)
                        )
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))


                SettingsSection(
                    items = listOf(
                        SettingsItem(
                            icon = Icons.Outlined.Notifications,
                            title = "Notifications and Sounds",
                            iconTint = Color(0xFFEA4335)
                        ), SettingsItem(
                            icon = Icons.Outlined.Lock,
                            title = "Privacy and Security",
                            iconTint = Color(0xFF9E9E9E)
                        ), SettingsItem(
                            icon = Icons.Outlined.Star,
                            title = "Data and Storage",
                            iconTint = Color(0xFF34A853)
                        ), SettingsItem(
                            icon = Icons.Outlined.Place,
                            title = "Appearance",
                            iconTint = Color(0xFF2196F3)
                        ),
                         SettingsItem(
                            icon = Icons.Outlined.Home,
                            title = "Help & FAQ",
                            iconTint = Color.Gray
                        )
                    )
                )

                 Spacer(modifier = Modifier.height(24.dp))
            }


            FloatingActionButton(
                onClick = { /* FAB Action */ },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .attractable("FAB_Edit"),
                contentColor = Color.White
            ) {
                Icon(Icons.Filled.Edit, "Edit FAB")
            }
        }
    }
}


data class SettingsItem(
    val icon: ImageVector, val title: String, val iconTint: Color
)


@Composable
fun SettingsSection(
    items: List<SettingsItem>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            items.forEachIndexed { index, item ->
                SettingsItemRow(item = item)
                if (index < items.size - 1) {
                    Divider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = Color(0xFFEEEEEE),
                        thickness = 0.8.dp
                    )
                }
            }
        }
    }
}


@Composable
fun SettingsItemRow(item: SettingsItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(item.iconTint.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = item.iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = item.title, fontSize = 17.sp,
            color = Color.Black.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.weight(1f))

        Icon(
            imageVector = Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.LightGray.copy(alpha = 0.7f)
        )
    }
}