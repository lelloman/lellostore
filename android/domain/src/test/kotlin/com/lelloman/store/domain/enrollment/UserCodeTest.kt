package com.lelloman.store.domain.enrollment

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserCodeTest {

    @Test
    fun `any case, spaces and dashes give the displayed form`() {
        assertThat(UserCode.normalize("WDJB-MJHT")).isEqualTo("WDJB-MJHT")
        assertThat(UserCode.normalize("wdjbmjht")).isEqualTo("WDJB-MJHT")
        assertThat(UserCode.normalize(" wd jb - mj ht ")).isEqualTo("WDJB-MJHT")
    }

    @Test
    fun `wrong lengths and characters outside the alphabet are refused`() {
        assertThat(UserCode.normalize("WDJB-MJH")).isNull()
        assertThat(UserCode.normalize("WDJB-MJHTX")).isNull()
        // Vowels and digits are not in the alphabet.
        assertThat(UserCode.normalize("WDJB-MJHA")).isNull()
        assertThat(UserCode.normalize("WDJB-MJH1")).isNull()
        assertThat(UserCode.normalize("../../me")).isNull()
        assertThat(UserCode.normalize("")).isNull()
    }
}
