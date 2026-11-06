// Offline adapter: call the production SDK, without a host, transport or device.
#include "moonlight_haptics/authored_events.h"
#include <algorithm>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <map>
#include <memory>
#include <stdexcept>
#include <vector>

int main(int argc, char** argv) {
    try {
        if (argc != 5) throw std::runtime_error("usage: analyze_pcm input.pcm frames.csv events.csv chunk(1..240)");
        const auto chunk = static_cast<unsigned>(std::stoul(argv[4]));
        if (chunk == 0 || chunk > 240) throw std::runtime_error("invalid chunk");
        std::ifstream source(argv[1], std::ios::binary);
        if (!source) throw std::runtime_error("cannot open PCM");
        const std::vector<unsigned char> bytes{std::istreambuf_iterator<char>(source), {}};
        if (bytes.empty() || bytes.size() % 4) throw std::runtime_error("expected stereo S16LE");
        std::vector<int16_t> pcm(bytes.size() / 2);
        for (size_t i = 0; i < pcm.size(); ++i) {
            const int value = int(bytes[2 * i]) | (int(bytes[2 * i + 1]) << 8);
            pcm[i] = static_cast<int16_t>(value >= 32768 ? value - 65536 : value);
        }
        AhAuthoredConfig config{};
        if (ah_authored_config_init(&config, 48000) != AH_STATUS_OK) throw std::runtime_error("config");
        AhAuthoredEventEngine* raw = nullptr;
        if (ah_authored_events_create(&config, &raw) != AH_STATUS_OK) throw std::runtime_error("create");
        std::unique_ptr<AhAuthoredEventEngine, decltype(&ah_authored_events_destroy)> engine(raw, ah_authored_events_destroy);
        AhAuthoredEngine* legacy_raw = nullptr;
        if (ah_authored_create(&config, &legacy_raw) != AH_STATUS_OK) throw std::runtime_error("legacy create");
        std::unique_ptr<AhAuthoredEngine, decltype(&ah_authored_destroy)> legacy_engine(legacy_raw, ah_authored_destroy);
        AhAuthoredHapticFrame legacy{};
        std::ofstream frames(argv[2]), events(argv[3]);
        if (!frames || !events) throw std::runtime_error("cannot open output");
        frames << "time_us,low_l,high_l,low_r,high_r,raw_rms_l,raw_rms_r,zcr_l,zcr_r\n" << std::setprecision(9);
        events << "id,source_us,first_seen_us,left,right,sharpness\n" << std::setprecision(9);
        std::map<uint32_t, AhAuthoredEvent> seen;
        unsigned sequence = 0;
        const auto count = static_cast<unsigned>(pcm.size() / 2);
        for (unsigned start = 0; start < count; start += chunk) {
            AhAuthoredProcessInput input{};
            input.struct_size = AH_AUTHORED_PROCESS_INPUT_V2_SIZE;
            input.interleaved_pcm = pcm.data() + start * 2;
            input.frame_count = std::min(chunk, count - start);
            input.sequence_number = sequence++;
            input.first_sample_time_us = uint64_t(start) * 1000000 / 48000;
            input.flags = start == 0 ? AH_AUTHORED_INPUT_STREAM_START : 0;
            AhAuthoredEventFrame output{};
            uint32_t produced = 0;
            if (ah_authored_events_process_i16(engine.get(), &input, &output, 1, &produced) != AH_STATUS_OUTPUT_AVAILABLE || produced != 1)
                throw std::runtime_error("process");
            AhAuthoredHapticFrame legacy_outputs[2]{};
            uint32_t legacy_count = 0;
            const auto legacy_status = ah_authored_process_i16(legacy_engine.get(), &input, legacy_outputs, 2, &legacy_count);
            if (legacy_status != AH_STATUS_OK && legacy_status != AH_STATUS_OUTPUT_AVAILABLE)
                throw std::runtime_error("legacy process");
            if (legacy_count) legacy = legacy_outputs[legacy_count - 1];
            frames << output.timestamp_us;
            for (const auto& lane : output.lanes) frames << ',' << lane.continuous_low << ',' << lane.continuous_high;
            frames << ',' << output.lanes[0].raw_rms << ',' << output.lanes[1].raw_rms
                   << ',' << legacy.lanes[0].zero_crossing_rate_hz << ',' << legacy.lanes[1].zero_crossing_rate_hz << '\n';
            for (unsigned i = 0; i < output.event_count; ++i) {
                const auto& event = output.events[i];
                const auto entry = seen.emplace(event.event_id, event);
                if (!entry.second) {
                    const auto& old = entry.first->second;
                    if (old.source_time_us != event.source_time_us || old.left_strength != event.left_strength || old.right_strength != event.right_strength)
                        throw std::runtime_error("published event changed");
                } else {
                    if (event.source_time_us > output.timestamp_us || output.timestamp_us - event.source_time_us >= 30000)
                        throw std::runtime_error("event freshness");
                    events << event.event_id << ',' << event.source_time_us << ',' << output.timestamp_us << ','
                           << event.left_strength << ',' << event.right_strength << ',' << event.sharpness << '\n';
                }
            }
        }
        AhAuthoredProcessInput end{};
        end.struct_size = AH_AUTHORED_PROCESS_INPUT_V2_SIZE;
        end.flags = AH_AUTHORED_INPUT_STREAM_END;
        end.sequence_number = sequence;
        AhAuthoredEventFrame output{};
        uint32_t produced = 0;
        if (ah_authored_events_process_i16(engine.get(), &end, &output, 1, &produced) != AH_STATUS_OUTPUT_AVAILABLE ||
            produced != 1 || output.event_count || output.lanes[0].continuous_low || output.lanes[1].continuous_high)
            throw std::runtime_error("END did not clear state");
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
