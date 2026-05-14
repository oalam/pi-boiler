#include "boiler/boiler_controller.hpp"

namespace boiler {

namespace {

template <typename T>
T clamp_value(T v, T lo, T hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

BoilerSettings clamp_settings(BoilerSettings s) {
    s.consigne_celsius = clamp_value(s.consigne_celsius,
                                     params::CONSIGNE_MIN_CELSIUS,
                                     params::CONSIGNE_MAX_CELSIUS);
    s.delay_off_ms = clamp_value(s.delay_off_ms,
                                 params::DELAY_OFF_MIN_MS,
                                 params::DELAY_OFF_MAX_MS);
    s.rest_duration_ms = clamp_value(s.rest_duration_ms,
                                     params::REST_DURATION_MIN_MS,
                                     params::REST_DURATION_MAX_MS);
    return s;
}

}  // namespace

BoilerController::BoilerController(IActuators& actuators, IReporter& reporter,
                                   const BoilerSettings& settings, uint64_t now_ms)
    : actuators_(actuators),
      reporter_(reporter),
      settings_(clamp_settings(settings)),
      state_(State::Stopped),
      state_entered_at_(now_ms) {
    for (bool& e : engine_on_) e = false;
    // L'installation démarre dans un état sûr connu.
    actuators_.shutdown();
}

// --- Pilotage des organes -------------------------------------------------

void BoilerController::apply_engine(Engine engine, bool on) {
    const int idx = static_cast<int>(engine);
    if (engine_on_[idx] != on) {
        engine_on_[idx] = on;
        actuators_.set_engine(engine, on);
    }
}

void BoilerController::all_combustion_off() {
    apply_engine(Engine::Tremie, false);
    apply_engine(Engine::Bruleur, false);
    apply_engine(Engine::Ventilo, false);
}

// Circulation hydraulique : fonction pure de la température courante.
//   T >= 60 : circulateur ON, laddomat ON
//   40 <= T < 60 : circulateur ON, laddomat OFF
//   T < 40 : circulateur OFF, laddomat OFF (anti-condensation)
void BoilerController::update_hydraulics(float celsius) {
    apply_engine(Engine::Circulateur, celsius >= params::CIRCULATEUR_ON_CELSIUS);
    apply_engine(Engine::Laddomat, celsius >= params::LADDOMAT_ON_CELSIUS);
}

// --- Cycle de combustion (Igniting / Running) -----------------------------

void BoilerController::begin_burning_cycle(uint64_t now_ms) {
    cycle_started_at_ = now_ms;
    cycle_active_ = true;
    apply_engine(Engine::Tremie, true);
    apply_engine(Engine::Bruleur, true);
    apply_engine(Engine::Ventilo, true);
}

void BoilerController::service_burning_cycle(uint64_t now_ms) {
    if (!cycle_active_) {
        begin_burning_cycle(now_ms);
        return;
    }
    const uint64_t elapsed = now_ms - cycle_started_at_;
    if (elapsed >= params::HEATING_RUN_DURATION_MS)
        apply_engine(Engine::Tremie, false);
    if (elapsed >= params::HEATING_RUN_DURATION_MS + params::BURNER_POST_DURATION_MS)
        apply_engine(Engine::Bruleur, false);
    // Le ventilateur reste actif pendant toute la phase de combustion.
    if (elapsed >= params::HEATING_RUN_DURATION_MS + settings_.delay_off_ms)
        begin_burning_cycle(now_ms);
}

// --- Mode ralenti (Slowing) -----------------------------------------------

void BoilerController::begin_slow_burst(uint64_t now_ms) {
    burst_started_at_ = now_ms;
    burst_active_ = true;
    apply_engine(Engine::Tremie, true);
    apply_engine(Engine::Bruleur, true);
    apply_engine(Engine::Ventilo, true);
}

void BoilerController::service_slow_mode(uint64_t now_ms) {
    if (burst_active_) {
        const uint64_t elapsed = now_ms - burst_started_at_;
        if (elapsed >= params::SLOWDOWN_ENGINES_DURATION_MS)
            apply_engine(Engine::Tremie, false);
        if (elapsed >= params::SLOWDOWN_ENGINES_DURATION_MS + params::BURNER_POST_DURATION_MS)
            apply_engine(Engine::Bruleur, false);
        if (elapsed >= params::SLOWDOWN_FAN_DURATION_MS) {
            apply_engine(Engine::Ventilo, false);
            burst_active_ = false;
            next_burst_at_ = now_ms + settings_.rest_duration_ms;
        }
    } else if (now_ms >= next_burst_at_) {
        begin_slow_burst(now_ms);
    }
}

// --- Amorçage manuel (Stopped) --------------------------------------------

void BoilerController::service_load(uint64_t now_ms) {
    const uint64_t elapsed = now_ms - load_started_at_;
    if (elapsed >= params::PELLETS_LOADING_DURATION_MS)
        apply_engine(Engine::Tremie, false);
    if (elapsed >= params::PELLETS_LOADING_DURATION_MS + params::BURNER_POST_DURATION_MS) {
        apply_engine(Engine::Bruleur, false);
        load_active_ = false;
    }
}

// --- Transitions ----------------------------------------------------------

void BoilerController::transition(State to, uint64_t now_ms) {
    if (to == state_) return;
    const State from = state_;
    state_ = to;
    state_entered_at_ = now_ms;
    reporter_.on_state_change(from, to, last_temperature_);

    switch (to) {
        case State::Stopped:
            all_combustion_off();
            cycle_active_ = false;
            burst_active_ = false;
            load_active_ = false;
            break;
        case State::Igniting:
        case State::Running:
            // Conserve un cycle déjà en cours (transition Igniting -> Running).
            if (!cycle_active_) begin_burning_cycle(now_ms);
            break;
        case State::Slowing:
            // On vient d'atteindre la consigne : on coupe et on laisse reposer
            // avant la première relance.
            all_combustion_off();
            cycle_active_ = false;
            burst_active_ = false;
            next_burst_at_ = now_ms + settings_.rest_duration_ms;
            break;
        case State::Overheating:
            all_combustion_off();
            cycle_active_ = false;
            burst_active_ = false;
            apply_engine(Engine::Circulateur, true);
            apply_engine(Engine::Laddomat, true);
            break;
        case State::Locked:
            all_combustion_off();
            cycle_active_ = false;
            burst_active_ = false;
            break;
    }
}

// --- Entrées --------------------------------------------------------------

void BoilerController::tick(uint64_t now_ms) {
    switch (state_) {
        case State::Igniting:
            service_burning_cycle(now_ms);
            if (now_ms - state_entered_at_ >= params::IGNITION_TIMEOUT_MS) {
                reporter_.on_alert(Alert::LockoutIgnitionFailed, last_temperature_);
                transition(State::Locked, now_ms);
            }
            break;
        case State::Running:
            service_burning_cycle(now_ms);
            break;
        case State::Slowing:
            service_slow_mode(now_ms);
            break;
        case State::Stopped:
            if (load_active_) service_load(now_ms);
            break;
        case State::Overheating:
        case State::Locked:
            break;
    }
}

void BoilerController::on_temperature(uint64_t now_ms, float celsius) {
    tick(now_ms);
    consecutive_failures_ = 0;
    failure_alerted_ = false;
    last_temperature_ = celsius;

    // En surchauffe, la circulation est forcée — on ne la repilote pas tant
    // que l'état n'a pas été quitté.
    if (state_ != State::Overheating)
        update_hydraulics(celsius);

    switch (state_) {
        case State::Igniting:
            if (celsius >= params::OVERHEAT_CELSIUS) {
                reporter_.on_alert(Alert::Overheat, celsius);
                transition(State::Overheating, now_ms);
            } else if (celsius >= settings_.consigne_celsius) {
                transition(State::Slowing, now_ms);
            } else if (celsius >= params::IGNITION_MIN_CELSIUS) {
                transition(State::Running, now_ms);
            }
            break;
        case State::Running:
            if (celsius >= params::OVERHEAT_CELSIUS) {
                reporter_.on_alert(Alert::Overheat, celsius);
                transition(State::Overheating, now_ms);
            } else if (celsius >= settings_.consigne_celsius) {
                transition(State::Slowing, now_ms);
            } else if (celsius <= params::LOW_TEMP_LOCKOUT_CELSIUS &&
                       now_ms - state_entered_at_ >= params::LOW_TEMP_LOCKOUT_DELAY_MS) {
                reporter_.on_alert(Alert::LockoutLowTemp, celsius);
                transition(State::Locked, now_ms);
            }
            break;
        case State::Slowing:
            if (celsius >= params::OVERHEAT_CELSIUS) {
                reporter_.on_alert(Alert::Overheat, celsius);
                transition(State::Overheating, now_ms);
            } else if (celsius <= settings_.consigne_celsius - 3.0f) {
                transition(State::Running, now_ms);
            }
            break;
        case State::Overheating:
            if (celsius <= params::OVERHEAT_CLEAR_CELSIUS) {
                transition(celsius >= settings_.consigne_celsius ? State::Slowing
                                                                 : State::Running,
                           now_ms);
            }
            break;
        case State::Stopped:
        case State::Locked:
            // Circulation déjà repilotée ci-dessus ; aucune combustion.
            break;
    }
}

void BoilerController::on_sensor_failure(uint64_t now_ms) {
    tick(now_ms);
    ++consecutive_failures_;
    if (consecutive_failures_ >= params::SENSOR_FAILURE_ALERT_THRESHOLD && !failure_alerted_) {
        failure_alerted_ = true;
        reporter_.on_alert(Alert::SensorFailure, last_temperature_);
        // Comportement sécurisé : sans température fiable, on se replie en
        // ralenti — alimentation minimale, feu maintenu. Le thermostat 95 °C
        // matériel reste le garde-fou de surchauffe.
        if (state_ == State::Igniting || state_ == State::Running)
            transition(State::Slowing, now_ms);
    }
}

void BoilerController::handle_command(uint64_t now_ms, Command command) {
    tick(now_ms);
    switch (command) {
        case Command::Start:
            // Démarrage à froid, ou réarmement manuel depuis Locked.
            if (state_ == State::Stopped || state_ == State::Locked) {
                load_active_ = false;
                transition(State::Igniting, now_ms);
            }
            break;
        case Command::Stop:
            if (state_ != State::Stopped)
                transition(State::Stopped, now_ms);
            break;
        case Command::Shutdown:
            for (bool& e : engine_on_) e = false;
            actuators_.shutdown();
            transition(State::Stopped, now_ms);
            break;
        case Command::LoadPellets:
            // Amorçage manuel du brûleur : uniquement à l'arrêt.
            if (state_ == State::Stopped && !load_active_) {
                load_active_ = true;
                load_started_at_ = now_ms;
                apply_engine(Engine::Tremie, true);
                apply_engine(Engine::Bruleur, true);
                apply_engine(Engine::Ventilo, false);
            }
            break;
    }
}

void BoilerController::update_settings(uint64_t now_ms, const BoilerSettings& settings) {
    tick(now_ms);
    settings_ = clamp_settings(settings);
}

}  // namespace boiler
