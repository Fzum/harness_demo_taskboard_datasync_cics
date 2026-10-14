package com.example.datasync.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostKeyTest {

  @Test
  void aTaskboardTaskGetsTPlusItsIdInNineDigits() {
    assertThat(HostKey.forTask(42)).isEqualTo("T000000042");
    assertThat(HostKey.forTask(999_999_999)).isEqualTo("T999999999");
  }

  @Test
  void readsTheTaskIdBackFromATKey() {
    assertThat(HostKey.taskIdOf("T000000042")).contains(42L);
    assertThat(HostKey.taskIdOf(HostKey.forTask(123_456_789))).contains(123_456_789L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"H000001001", "T00000042", "T0000000042", "TX00000042", "t000000042", ""})
  void otherKeysCarryNoTaskId(String key) {
    assertThat(HostKey.taskIdOf(key)).isEmpty();
  }
}
