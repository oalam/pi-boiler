#pragma once
#include <cstdint>
#include "boiler/types.hpp"
#include "boiler/interfaces.hpp"

// Cœur de régulation de la chaudière à granulés — machine à états.
//
// BoilerController est une logique pure : pas d'allocation dynamique, pas
// d'exceptions, aucune dépendance matérielle. Le temps lui est INJECTÉ
// (uint64_t millisecondes monotones), ce qui le rend 100 % déterministe et
// testable sur PC, puis portable tel quel sur ESP32 ou Raspberry Pi.
//
// L'hôte est responsable de :
//   - fournir une horloge monotone 64 bits en millisecondes ;
//   - appeler tick() régulièrement (p. ex. toutes les 200–500 ms) ;
//   - appeler on_temperature() / on_sensor_failure() à chaque lecture de sonde ;
//   - relayer les commandes externes via handle_command() ;
//   - implémenter IActuators et IReporter.

namespace boiler {

class BoilerController {
public:
    BoilerController(IActuators& actuators, IReporter& reporter,
                     const BoilerSettings& settings, uint64_t now_ms);

    // Maintenance des minuteries (cycles de combustion, ralenti, amorçage,
    // watchdog d'allumage). À appeler régulièrement par l'hôte.
    void tick(uint64_t now_ms);

    // Nouvelle lecture valide de la sonde de température.
    void on_temperature(uint64_t now_ms, float celsius);

    // Échec de lecture de la sonde de température.
    void on_sensor_failure(uint64_t now_ms);

    // Défaut sur une vis sans fin : protection thermique déclenchée ou bourrage
    // (contacts secs `tremieSecu` / `bruleurSecu`). Verrouille la chaudière.
    // À appeler sur le front du défaut, pas en continu.
    void on_engine_fault(uint64_t now_ms);

    // Commande externe (REST, bouton, superviseur).
    void handle_command(uint64_t now_ms, Command command);

    // Mise à jour des réglages utilisateur (valeurs bornées en interne).
    void update_settings(uint64_t now_ms, const BoilerSettings& settings);

    // Accès en lecture seule (diagnostic / supervision).
    State                 state() const { return state_; }
    float                 last_temperature() const { return last_temperature_; }
    const BoilerSettings& settings() const { return settings_; }

    // Comptabilisation de la consommation de granulés.
    // Le débit de la vis trémie étant ~constant, la consommation s'estime à
    // partir de son temps de marche cumulé depuis la construction.
    uint64_t tremie_run_ms() const { return tremie_run_ms_; }
    float    estimated_consumption_kg() const;

private:
    void transition(State to, uint64_t now_ms);
    void apply_engine(Engine engine, bool on);
    void all_combustion_off();
    void update_hydraulics(float celsius);
    void begin_burning_cycle(uint64_t now_ms);
    void service_burning_cycle(uint64_t now_ms);
    void begin_slow_burst(uint64_t now_ms);
    void service_slow_mode(uint64_t now_ms);
    void service_load(uint64_t now_ms);

    // Doit correspondre au nombre de valeurs de l'énumération Engine.
    static constexpr int kEngineCount = 5;

    IActuators&    actuators_;
    IReporter&     reporter_;
    BoilerSettings settings_;

    State    state_;
    uint64_t state_entered_at_;
    uint64_t runtime_sampled_at_;
    float    last_temperature_ = 0.0f;

    // États désirés des organes — sert à n'appeler set_engine() que sur changement.
    bool engine_on_[kEngineCount];

    // Cycle de combustion (états Igniting / Running).
    uint64_t cycle_started_at_ = 0;
    bool     cycle_active_ = false;

    // Mode ralenti (état Slowing).
    uint64_t next_burst_at_ = 0;
    uint64_t burst_started_at_ = 0;
    bool     burst_active_ = false;

    // Amorçage manuel (état Stopped).
    uint64_t load_started_at_ = 0;
    bool     load_active_ = false;

    // Surveillance de la sonde.
    uint32_t consecutive_failures_ = 0;
    bool     failure_alerted_ = false;

    // Comptabilisation de la consommation (temps de marche cumulé de la trémie).
    uint64_t tremie_run_ms_ = 0;
};

}  // namespace boiler
