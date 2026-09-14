package com.aimusic.player.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * `ApiKeyStore`（`03 §7`）：只存取 `api_key`，且**只回显掩码**。
 *
 * 必须在真机上跑：`EncryptedSharedPreferences` 依赖 Android Keystore，JVM 上没有它。
 * 断言只针对「存取 + 掩码」，不去检验密文本身 —— 那是库的实现细节，测它没有意义。
 *
 * 文件名**每轮随机**：密文落在 `shared_prefs/` 下，靠 setUp 里删文件很难删对路径，
 * 上一次运行残留的数据会把「未配置时」类断言顶掉（踩过一次）。
 */
@RunWith(AndroidJUnit4::class)
class ApiKeyStoreTest {

    private lateinit var store: ApiKeyStore
    private lateinit var fileName: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fileName = "llm_secure_test_${UUID.randomUUID().toString().replace("-", "")}"
        store = ApiKeyStore(context, fileName = fileName)
    }

    @Test
    fun `存取与清除往返一致`() = runDbTest {
        assertThat(store.read()).isNull()

        store.write("sk-live-abcdefghijklmnop")

        assertThat(store.read()).isEqualTo("sk-live-abcdefghijklmnop")

        store.clear()

        assertThat(store.read()).isNull()
    }

    @Test
    fun `掩码只保留前缀_不泄露其余字符`() = runDbTest {
        store.write("sk-live-abcdefghijklmnop")

        val masked = store.masked()!!

        assertThat(masked).isEqualTo("sk-****")
        // 掩码里不能出现原文的任何一段（除固定前缀外）
        assertThat(masked).doesNotContain("live")
        assertThat(masked).doesNotContain("abcdefg")
    }

    @Test
    fun `没有配置时掩码为空`() = runDbTest {
        assertThat(store.masked()).isNull()
    }

    @Test
    fun `非_sk_前缀的串也走同一套掩码_不原样回显`() = runDbTest {
        store.write("custom-token-xyz")

        val masked = store.masked()!!

        assertThat(masked).doesNotContain("custom")
        assertThat(masked).doesNotContain("xyz")
        assertThat(masked).endsWith("****")
    }
    // 曾经有一条「同一进程内为同一文件再建一个实例仍能读回」的用例，已删除：
    // EncryptedSharedPreferences 不保证同一文件存在多个实例时的行为，那条用例断言的是
    // **库并不提供的**性质，而不是本类的逻辑。真实约束反过来更重要（见 ApiKeyStore 的类注释）：
    // 每个文件名只应有**一个**实例，生产上由 Hilt 以单例提供。
}
