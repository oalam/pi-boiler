#include "boiler/boiler_controller.hpp"
#include "test_framework.hpp"

using namespace boiler;

namespace {

// --- Doublures de test ----------------------------------------------------

struct FakeActuators : IActuators {
    bool on[5] = {false, false, false, false, false};
    int  set_calls = 0;
    bool shutdown_called = false;

    void set_engine(Engine e, bool s) override {
        on[static_cast<int>(e)] = s;
        ++set_calls;
    }
    void shutdown() override {
        shutdown_called = true;
        for (bool& x : on) x = false;
    }
};

struct FakeReporter : IReporter {
    int   state_changes = 0;
    State last_from = State::Stopped;
    State last_to = State::Stopped;
    int   alerts = 0;
    Alert last_alert = Alert::Overheat;

    void on_state_change(State f, State t, float) override {
        ++state_changes;
        last_from = f;
        last_to = t;
    }
    void on_alert(Alert a, float) override {
        ++alerts;
        last_alert = a;
    }
};

bool tremie(const FakeActuators& a)      { return a.on[static_cast<int>(Engine::Tremie)]; }
bool bruleur(const FakeActuators& a)     { return a.on[static_cast<int>(Engine::Bruleur)]; }
bool ventilo(const FakeActuators& a)     { return a.on[static_cast<int>(Engine::Ventilo)]; }
bool circulateur(const FakeActuators& a) { return a.on[static_cast<int>(Engine::Circulateur)]; }
bool laddomat(const FakeActuators& a)    { return a.on[static_cast<int>(Engine::Laddomat)]; }

// --- Tests ----------------------------------------------------------------

void test_starts_stopped() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    CHECK(c.state() == State::Stopped);
    CHECK(act.shutdown_called);  // état sûr connu à l'initialisation
}

void test_start_enters_igniting_and_runs_engines() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    CHECK(c.state() == State::Igniting);
    CHECK(tremie(act));
    CHECK(bruleur(act));
    CHECK(ventilo(act));
}

void test_ignition_success() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(10000, 35.0f);  // >= 30 °C : allumage confirmé
    CHECK(c.state() == State::Running);
}

void test_ignition_timeout_locks() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.tick(60u * 60u * 1000u);  // 60 min sans montée en température
    CHECK(c.state() == State::Locked);
    CHECK(rep.alerts == 1);
    CHECK(rep.last_alert == Alert::LockoutIgnitionFailed);
    CHECK(!tremie(act) && !bruleur(act) && !ventilo(act));
}

void test_burning_cycle_timing() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 35.0f);  // -> Running, cycle démarré à t=0
    CHECK(c.state() == State::Running);

    c.tick(2999);
    CHECK(tremie(act));
    c.tick(3000);
    CHECK(!tremie(act));   // vis trémie coupée après 3 s
    CHECK(bruleur(act));
    c.tick(8000);
    CHECK(!bruleur(act));  // vis brûleur coupée après 3 s + 5 s de post-fonctionnement
    CHECK(ventilo(act));   // le ventilateur reste actif
    c.tick(23000);
    CHECK(tremie(act));    // nouveau cycle après 3 s + delay_off (20 s)
    CHECK(bruleur(act));
}

void test_consigne_enters_slowing() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 35.0f);  // Running
    c.on_temperature(2000, 76.0f);  // >= consigne 75 °C
    CHECK(c.state() == State::Slowing);
    CHECK(!tremie(act) && !bruleur(act) && !ventilo(act));  // combustion coupée
}

void test_slowing_burst_after_rest() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 35.0f);
    c.on_temperature(2000, 76.0f);  // Slowing à t=2000, relance prévue à t=2000+rest
    const uint64_t burst = 2000 + 60u * 60u * 1000u;
    c.tick(burst - 1);
    CHECK(!tremie(act));  // encore au repos
    c.tick(burst);
    CHECK(tremie(act) && bruleur(act) && ventilo(act));  // relance
}

void test_slowing_burst_durations() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 35.0f);
    c.on_temperature(2000, 76.0f);
    const uint64_t burst = 2000 + 60u * 60u * 1000u;
    c.tick(burst);
    CHECK(tremie(act) && bruleur(act) && ventilo(act));
    c.tick(burst + 120000);  // 2 min : vis trémie coupée
    CHECK(!tremie(act) && bruleur(act) && ventilo(act));
    c.tick(burst + 125000);  // 2 min 5 s : vis brûleur ET ventilateur coupés
    CHECK(!tremie(act) && !bruleur(act) && !ventilo(act));
}

void test_exit_slowing_back_to_running() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 35.0f);
    c.on_temperature(2000, 76.0f);  // Slowing
    c.on_temperature(3000, 71.0f);  // <= consigne - 3
    CHECK(c.state() == State::Running);
}

void test_overheating_from_running() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 35.0f);
    c.on_temperature(2000, 92.0f);
    CHECK(c.state() == State::Overheating);
    CHECK(rep.last_alert == Alert::Overheat);
    CHECK(!tremie(act) && !bruleur(act) && !ventilo(act));  // combustion coupée
    CHECK(circulateur(act) && laddomat(act));               // circulation forcée
}

void test_overheating_clears_with_hysteresis() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 35.0f);
    c.on_temperature(2000, 92.0f);  // Overheating
    c.on_temperature(3000, 88.0f);  // > 85 : reste en surchauffe
    CHECK(c.state() == State::Overheating);
    c.on_temperature(4000, 84.0f);  // <= 85 : sortie ; 84 >= consigne -> Slowing
    CHECK(c.state() == State::Slowing);
}

void test_low_temp_lockout_after_delay() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 35.0f);                       // Running à t=1000
    c.on_temperature(1000 + 30u * 60u * 1000u, 25.0f);   // 30 min plus tard, <= 30 °C
    CHECK(c.state() == State::Locked);
    CHECK(rep.last_alert == Alert::LockoutLowTemp);
}

void test_low_temp_tolerated_before_delay() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 35.0f);                  // Running
    c.on_temperature(1000 + 10u * 60u * 1000u, 25.0f);  // 10 min : trop tôt pour verrouiller
    CHECK(c.state() == State::Running);
}

void test_locked_reset_with_start() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.tick(60u * 60u * 1000u);  // watchdog d'allumage -> Locked
    CHECK(c.state() == State::Locked);
    c.handle_command(60u * 60u * 1000u, Command::Start);  // réarmement manuel
    CHECK(c.state() == State::Igniting);
}

void test_hydraulics_follow_temperature() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.on_temperature(1000, 35.0f);
    CHECK(!circulateur(act) && !laddomat(act));  // < 40
    c.on_temperature(2000, 45.0f);
    CHECK(circulateur(act) && !laddomat(act));   // 40..60
    c.on_temperature(3000, 65.0f);
    CHECK(circulateur(act) && laddomat(act));    // >= 60
    c.on_temperature(4000, 35.0f);
    CHECK(!circulateur(act) && !laddomat(act));  // anti-condensation
}

void test_sensor_failure_alerts_and_falls_back_to_slowing() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 50.0f);  // Running
    for (int i = 1; i <= 5; ++i)
        c.on_sensor_failure(1000 + static_cast<uint64_t>(i) * 2000);
    CHECK(rep.last_alert == Alert::SensorFailure);
    CHECK(rep.alerts == 1);
    CHECK(c.state() == State::Slowing);
}

void test_sensor_failure_counter_resets_on_good_reading() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 50.0f);
    c.on_sensor_failure(2000);
    c.on_sensor_failure(4000);
    c.on_temperature(6000, 50.0f);  // remet le compteur à zéro
    c.on_sensor_failure(8000);
    c.on_sensor_failure(10000);
    CHECK(rep.alerts == 0);  // jamais 5 échecs consécutifs
    CHECK(c.state() == State::Running);
}

void test_engine_fault_locks() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 50.0f);  // Running
    c.on_engine_fault(2000);        // protection thermique / bourrage d'une vis
    CHECK(c.state() == State::Locked);
    CHECK(rep.last_alert == Alert::EngineFault);
    CHECK(!tremie(act) && !bruleur(act) && !ventilo(act));
}

void test_stop_returns_to_stopped() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::Start);
    c.on_temperature(1000, 50.0f);
    c.handle_command(2000, Command::Stop);
    CHECK(c.state() == State::Stopped);
    CHECK(!tremie(act) && !bruleur(act) && !ventilo(act));
}

void test_load_pellets_in_stopped() {
    FakeActuators act;
    FakeReporter rep;
    BoilerController c(act, rep, BoilerSettings{}, 0);
    c.handle_command(0, Command::LoadPellets);
    CHECK(tremie(act) && bruleur(act));
    CHECK(!ventilo(act));     // pas de ventilateur pendant l'amorçage
    c.tick(50000);
    CHECK(!tremie(act));      // vis trémie coupée après 50 s
    c.tick(55000);
    CHECK(!bruleur(act));     // vis brûleur coupée après 50 s + 5 s
    CHECK(c.state() == State::Stopped);
}

void test_settings_are_clamped() {
    FakeActuators act;
    FakeReporter rep;
    BoilerSettings s;
    s.consigne_celsius = 200.0f;
    s.delay_off_ms = 1;
    s.rest_duration_ms = 999u * 60u * 1000u;
    BoilerController c(act, rep, s, 0);
    CHECK(c.settings().consigne_celsius == 90.0f);
    CHECK(c.settings().delay_off_ms == 3000u);
    CHECK(c.settings().rest_duration_ms == 90u * 60u * 1000u);
}

}  // namespace

int main() {
    RUN(test_starts_stopped);
    RUN(test_start_enters_igniting_and_runs_engines);
    RUN(test_ignition_success);
    RUN(test_ignition_timeout_locks);
    RUN(test_burning_cycle_timing);
    RUN(test_consigne_enters_slowing);
    RUN(test_slowing_burst_after_rest);
    RUN(test_slowing_burst_durations);
    RUN(test_exit_slowing_back_to_running);
    RUN(test_overheating_from_running);
    RUN(test_overheating_clears_with_hysteresis);
    RUN(test_low_temp_lockout_after_delay);
    RUN(test_low_temp_tolerated_before_delay);
    RUN(test_locked_reset_with_start);
    RUN(test_hydraulics_follow_temperature);
    RUN(test_sensor_failure_alerts_and_falls_back_to_slowing);
    RUN(test_sensor_failure_counter_resets_on_good_reading);
    RUN(test_engine_fault_locks);
    RUN(test_stop_returns_to_stopped);
    RUN(test_load_pellets_in_stopped);
    RUN(test_settings_are_clamped);
    return SUMMARY();
}
