package dev.thompgt.habitsync.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HlcTest {

    @Nested
    @DisplayName("total ordering")
    class Ordering {

        @Test
        void ordersByPhysicalTimeFirst() {
            Hlc earlier = new Hlc(100, 999, "zzz");
            Hlc later = new Hlc(101, 0, "aaa");

            assertThat(earlier).isLessThan(later);
        }

        @Test
        void ordersByLogicalCounterWhenPhysicalTimesMatch() {
            Hlc first = new Hlc(100, 1, "zzz");
            Hlc second = new Hlc(100, 2, "aaa");

            assertThat(first).isLessThan(second);
        }

        @Test
        @DisplayName("nodeId breaks exact ties — without this, replicas can disagree")
        void nodeIdBreaksTies() {
            Hlc fromNodeA = new Hlc(100, 1, "node-a");
            Hlc fromNodeB = new Hlc(100, 1, "node-b");

            assertThat(fromNodeA.compareTo(fromNodeB)).isNegative();
            assertThat(fromNodeB.compareTo(fromNodeA)).isPositive();
            // The critical property: never 0 for distinct timestamps. A 0 here means two
            // devices merging the same pair of changes could pick different winners.
            assertThat(fromNodeA.compareTo(fromNodeB)).isNotZero();
        }

        @Test
        void comparisonIsConsistentWithEquals() {
            Hlc a = new Hlc(100, 1, "node-a");
            Hlc b = new Hlc(100, 1, "node-a");

            assertThat(a).isEqualTo(b);
            assertThat(a.compareTo(b)).isZero();
        }

        @Test
        void sortsIntoDeterministicOrderRegardlessOfInputOrder() {
            Hlc a = new Hlc(100, 0, "n1");
            Hlc b = new Hlc(100, 0, "n2");
            Hlc c = new Hlc(100, 1, "n1");
            Hlc d = new Hlc(101, 0, "n1");

            List<Hlc> forwards = new ArrayList<>(List.of(a, b, c, d));
            List<Hlc> backwards = new ArrayList<>(List.of(d, c, b, a));
            forwards.sort(null);
            backwards.sort(null);

            assertThat(forwards).containsExactly(a, b, c, d);
            assertThat(backwards).isEqualTo(forwards);
        }

        @Test
        void isAfterAndIsBeforeAgreeWithCompareTo() {
            Hlc earlier = new Hlc(100, 0, "n1");
            Hlc later = new Hlc(200, 0, "n1");

            assertThat(later.isAfter(earlier)).isTrue();
            assertThat(earlier.isBefore(later)).isTrue();
            assertThat(earlier.isAfter(later)).isFalse();
            assertThat(earlier.isAfter(earlier)).isFalse();
        }

        @Test
        void maxHandlesNullsSoCallersNeedNoBranches() {
            Hlc value = new Hlc(100, 0, "n1");

            assertThat(Hlc.max(null, value)).isEqualTo(value);
            assertThat(Hlc.max(value, null)).isEqualTo(value);
            assertThat(Hlc.max(null, null)).isNull();
            assertThat(Hlc.max(value, new Hlc(200, 0, "n1"))).isEqualTo(new Hlc(200, 0, "n1"));
        }
    }

    @Nested
    @DisplayName("compact encoding")
    class Encoding {

        @Test
        void roundTripsThroughParse() {
            Hlc original = new Hlc(1_753_400_000_000L, 42, "device-7");

            assertThat(Hlc.parse(original.toCompactString())).isEqualTo(original);
        }

        @Test
        void encodesInTheDocumentedFormat() {
            assertThat(new Hlc(100, 2, "node-a").toCompactString()).isEqualTo("100:2:node-a");
        }

        @Test
        void rejectsMalformedInput() {
            assertThatThrownBy(() -> Hlc.parse("100:2"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Malformed HLC");
            assertThatThrownBy(() -> Hlc.parse("not-a-number:2:node"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-numeric");
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void rejectsNegativeComponents() {
            assertThatThrownBy(() -> new Hlc(-1, 0, "n")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Hlc(0, -1, "n")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNodeIdContainingTheSeparator() {
            assertThatThrownBy(() -> new Hlc(0, 0, "node:a"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not contain");
        }

        @Test
        void rejectsEmptyOrNullNodeId() {
            assertThatThrownBy(() -> new Hlc(0, 0, "")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Hlc(0, 0, null)).isInstanceOf(NullPointerException.class);
        }
    }
}
