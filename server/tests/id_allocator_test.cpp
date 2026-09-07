#include <kserver/id_allocator.hpp>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

namespace fs = std::filesystem;

static int fails = 0;

static void expect(bool cond, const char* msg) {
    if (!cond) {
        std::cerr << "FAIL: " << msg << std::endl;
        fails++;
    }
}

int main() {
    fs::path dir = fs::temp_directory_path() / ("kserver-id-" + std::to_string(std::time(nullptr)));
    fs::create_directories(dir);
    fs::path file = dir / "id_counter";

    {
        kserver::IdAllocator a(file);
        expect(a.next() == 200000, "missing file starts at 200000");
        int id = a.assign(0, [](int) { return false; });
        expect(id == 200000, "first assign is 200000");
        expect(a.next() == 200001, "next after first");
    }
    {
        std::ifstream in(file);
        int stored = 0;
        in >> stored;
        expect(stored == 200001, "file stores next");
    }
    {
        kserver::IdAllocator a(file);
        expect(a.next() == 200001, "reload next");
        int id = a.assign(200005, [](int) { return false; });
        expect(id == 200005, "claim unused 200005");
        expect(a.next() == 200006, "watermark raised");
    }
    {
        kserver::IdAllocator a(file);
        int bad1 = a.assign(100778, [](int) { return false; });
        expect(bad1 == 200006, "reject secretary id");
        int bad2 = a.assign(7, [](int) { return false; });
        expect(bad2 == 200007, "reject old small id");
        int bad3 = a.assign(-1, [](int) { return false; });
        expect(bad3 == 200008, "reject negative");
        int taken = a.assign(200005, [](int id) { return id == 200005; });
        expect(taken == 200008 || taken == 200009, "in-use claim gets a new id");
        expect(taken != 200005, "must not reuse live id");
    }
    {
        fs::path oldf = dir / "old";
        std::ofstream(oldf) << "42\n";
        kserver::IdAllocator a(oldf);
        expect(a.next() == 200000, "legacy counter 42 becomes 200000");
        expect(a.assign(0, [](int) { return false; }) == 200000, "first after legacy");
    }

    fs::remove_all(dir);
    if (fails) {
        std::cerr << fails << " assertion(s) failed\n";
        return 1;
    }
    std::cout << "id_allocator_test OK\n";
    return 0;
}
