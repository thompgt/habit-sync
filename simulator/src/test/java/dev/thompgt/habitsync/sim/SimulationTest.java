package dev.thompgt.habitsync.sim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.thompgt.habitsync.sync.EntityKey;
import dev.thompgt.habitsync.sync.EntityType;
import dev.thompgt.habitsync.sync.FieldValue;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The simulator's own tests: that it is deterministic, that it actually provokes the
 * conditions it claims to, and that a run converges.
 *
 * <p>The middle one matters most and is the easiest to leave out. A fault-injection suite
 * that passes while injecting no faults is indistinguishable from one that works, and the
 * only defence is to assert on the counters as well as on the outcome.
 */
class SimulationTest {

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("the same seed produces a byte-identical run")
        void sameSeedSameRun() {
            SimulationResult first = Simulation.run(20260813L);
            SimulationResult second = Simulation.run(20260813L);

            // The history, not just the verdict. Two runs that both converge but took
            // different paths would mean a reported seed does not reproduce the failure it
            // was reported for, which is the one guarantee the simulator has to offer.
            assertThat(second.history()).isEqualTo(first.history());
            assertThat(second.serverStats()).isEqualTo(first.serverStats());
            assertThat(second.networkStats()).isEqualTo(first.networkStats());
            assertThat(second.replicaStats()).isEqualTo(first.replicaStats());
        }

        @Test
        @DisplayName("different seeds produce different runs")
        void differentSeedsDiffer() {
            assertThat(Simulation.run(1L).history()).isNotEqualTo(Simulation.run(2L).history());
        }
    }

    @Nested
    @DisplayName("the run actually exercises something")
    class Coverage {

        @Test
        @DisplayName("a standard run loses responses, partitions, and forces resyncs")
        void faultsAreReallyInjected() {
            SimulationResult result = new Simulation(4242L, SimulationConfig.standard().withSteps(600)).run();

            assertThat(result.networkStats().responsesLost())
                    .as("a lost response commits on the server and fails on the client; without "
                            + "one, op-id idempotency is never tested. %s", result.report())
                    .isPositive();
            assertThat(result.networkStats().blockedByPartition())
                    .as("no replica ever went offline, so nothing was ever merged from a backlog")
                    .isPositive();
            assertThat(result.networkStats().duplicates()).isPositive();
            assertThat(result.serverStats().collectedEntries())
                    .as("retention never ran, so the GC horizon path is untested")
                    .isPositive();
            assertThat(result.serverStats().resyncsDemanded())
                    .as("no replica was ever stranded below the horizon")
                    .isPositive();
        }

        @Test
        @DisplayName("the workload writes enough to be worth checking")
        void workloadIsSubstantial() {
            SimulationResult result = Simulation.run(99L);

            assertThat(result.serverStats().logSize() + result.serverStats().collectedEntries())
                    .as("%s", result.report())
                    .isGreaterThan(50);
            assertThat(result.replicaStats()).allSatisfy(stats -> assertThat(stats.localEdits()).isPositive());
        }
    }

    @Nested
    @DisplayName("the oracle can fail")
    class OracleNegativeControl {

        /**
         * Without this, every convergence assertion in the file is unfalsifiable. An oracle
         * that compares nothing passes every run, and the suite would look exactly as it does
         * now.
         */
        @Test
        @DisplayName("an unpushed local edit is reported as a divergence")
        void plantedDivergenceIsCaught() {
            Simulation simulation = new Simulation(2024L, SimulationConfig.standard());
            SimulationResult clean = simulation.run();
            assertThat(clean.passed()).as("%s", clean.report()).isTrue();

            // Take an entity both sides agree on and change it on one replica only. The
            // server never hears about it, so the field and the clock that wrote it must
            // both differ.
            Replica replica = simulation.replicas().get(0);
            EntityKey key = simulation.server().state().keySet().stream()
                    .filter(candidate -> candidate.type() == EntityType.HABIT)
                    .findFirst()
                    .orElseThrow();
            replica.engine().upsert(key.type(), key.id(), "name", FieldValue.of("edited locally, never sent"));

            List<String> divergences = ConvergenceOracle.compare(simulation.server(), simulation.replicas());

            assertThat(divergences).isNotEmpty();
            assertThat(divergences.toString()).contains("device-0").contains("name");
        }

        @Test
        @DisplayName("a replica that never synced is reported as missing everything")
        void neverSyncedReplicaIsCaught() {
            Simulation simulation = new Simulation(2025L, SimulationConfig.standard());
            simulation.run();

            Replica stranger = new Replica(
                    "device-stranger",
                    VirtualClock.skewed(0),
                    request -> {
                        throw new UnsupportedOperationException("never syncs");
                    },
                    UUID::randomUUID);

            List<String> divergences = ConvergenceOracle.compare(
                    simulation.server(), List.of(stranger));

            assertThat(divergences)
                    .as("the server holds an account's worth of entities this replica has never seen")
                    .isNotEmpty();
            assertThat(divergences).allSatisfy(line -> assertThat(line).contains("is missing"));
        }
    }

    @Nested
    @DisplayName("convergence")
    class Convergence {

        @Test
        @DisplayName("a perfect network converges — the control case")
        void perfectNetworkConverges() {
            SimulationResult result =
                    new Simulation(7L, SimulationConfig.standard().withFaults(FaultProfile.perfect())).run();

            // If this fails, the fault knobs are innocent and the engine is wrong.
            assertThat(result.passed()).as("%s", result.report()).isTrue();
        }

        @Test
        @DisplayName("a flaky network converges")
        void flakyNetworkConverges() {
            SimulationResult result = Simulation.run(12345L);
            assertThat(result.passed()).as("%s", result.report()).isTrue();
        }

        @Test
        @DisplayName("a hostile network converges")
        void hostileNetworkConverges() {
            SimulationResult result =
                    new Simulation(555L, SimulationConfig.standard().withFaults(FaultProfile.hostile())).run();
            assertThat(result.passed()).as("%s", result.report()).isTrue();
        }

        @Test
        @DisplayName("five replicas converge")
        void manyReplicasConverge() {
            SimulationResult result =
                    new Simulation(31337L, SimulationConfig.standard().withReplicas(5)).run();
            assertThat(result.passed()).as("%s", result.report()).isTrue();
        }
    }
}
