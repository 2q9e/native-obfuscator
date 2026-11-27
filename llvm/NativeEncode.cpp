#include "NativeTransforms.h"

#include "llvm/ADT/StringSet.h"
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Plugins/PassPlugin.h"
#include "llvm/Support/MemoryBuffer.h"
#include "llvm/Support/raw_ostream.h"

#include <algorithm>
#include <optional>
#include <string>
#include <utility>

namespace {

using namespace nativeobfuscator;

struct PassDefinition {
  llvm::StringLiteral Name;
  Transform Kind;
  unsigned DefaultMaxSites;
};

constexpr PassDefinition Passes[] = {
    {"native-encode", Transform::Encode, 8},
    {"native-diversify", Transform::Diversify, 2},
    {"native-mix", Transform::Mix, 4},
    {"native-route", Transform::Route, 1},
    {"native-compare", Transform::Compare, 1},
    {"native-dispatch", Transform::Dispatch, 16},
};

struct PassConfig {
  PassDefinition Definition;
  TransformOptions Rewrite;
  std::string Prefix;
  std::optional<llvm::StringSet<>> Functions;
};

bool isSymbolCharacter(char C) {
  return (C >= 'a' && C <= 'z') || (C >= 'A' && C <= 'Z') ||
         (C >= '0' && C <= '9') || C == '_' || C == '.' || C == '$';
}

bool isSymbol(llvm::StringRef Name) {
  return !Name.empty() &&
         std::all_of(Name.begin(), Name.end(), isSymbolCharacter);
}

bool readFunctions(llvm::StringRef Names, char Separator,
                   llvm::StringSet<> &Functions) {
  while (!Names.empty()) {
    auto [Name, Remaining] = Names.split(Separator);
    if (!isSymbol(Name) || !Functions.insert(Name).second)
      return false;
    Names = Remaining;
  }
  return true;
}

bool readFunctionFile(llvm::StringRef Basename, llvm::StringSet<> &Functions) {
  if (Basename.empty() || Basename == "." || Basename == ".." ||
      !std::all_of(Basename.begin(), Basename.end(),
                   [](char C) { return isSymbolCharacter(C) || C == '-'; }))
    return false;
  auto File = llvm::MemoryBuffer::getFile(Basename);
  if (!File) {
    llvm::errs() << "cannot read function list " << Basename << ": "
                 << File.getError().message() << '\n';
    return false;
  }
  auto Names = (*File)->getBuffer();
  if (Names == "\n" || Names.ends_with("\n\n") ||
      !readFunctions(Names, '\n', Functions)) {
    llvm::errs() << "invalid function list " << Basename
                 << ": expected unique LLVM symbols, one per line\n";
    return false;
  }
  return true;
}

bool readOptions(llvm::StringRef Name, PassConfig &Config) {
  Name.consume_front(Config.Definition.Name);
  if (Name.empty())
    return true;
  if (!Name.consume_front("<") || !Name.consume_back(">") || Name.empty() ||
      Name.ends_with(";"))
    return false;

  bool HasSeed = false;
  bool HasMax = false;
  while (!Name.empty()) {
    auto [Option, Remaining] = Name.split(';');
    auto [Key, Value] = Option.split('=');
    if (Key == "seed" && !HasSeed &&
        !Value.getAsInteger(10, Config.Rewrite.Seed)) {
      HasSeed = true;
    } else if (Key == "max" && !HasMax &&
               !Value.getAsInteger(10, Config.Rewrite.MaxSites)) {
      HasMax = true;
    } else if (Key == "prefix" && Config.Prefix.empty() && isSymbol(Value)) {
      Config.Prefix = Value.str();
    } else if (Key == "functions" && !Config.Functions && !Value.empty() &&
               !Value.ends_with("+")) {
      if (!readFunctions(Value, '+', Config.Functions.emplace()))
        return false;
    } else if (Key == "functions-file" && !Config.Functions) {
      if (!readFunctionFile(Value, Config.Functions.emplace()))
        return false;
    } else {
      return false;
    }
    Name = Remaining;
  }
  return true;
}

void writeSiteRejections(llvm::raw_ostream &Stream, Transform Kind,
                         const RewriteStatistics &Statistics,
                         const RewriteStatistics &Before = {}) {
  if (Kind != Transform::Dispatch)
    Stream << " skipped_flags="
           << Statistics.FlagFilteredSites - Before.FlagFilteredSites;
  Stream << " skipped_type="
         << Statistics.TypeFilteredSites - Before.TypeFilteredSites;
  if (Kind == Transform::Dispatch)
    Stream << " skipped_size="
           << Statistics.SizeFilteredSites - Before.SizeFilteredSites;
  if (Kind == Transform::Mix)
    Stream << " skipped_undefined="
           << Statistics.UndefinedOperandSites - Before.UndefinedOperandSites;
  if (Kind == Transform::Diversify || Kind == Transform::Route ||
      Kind == Transform::Compare)
    Stream << " skipped_unsafe_blocks="
           << Statistics.UnsafeBlockSites - Before.UnsafeBlockSites;
}

class NativeEncode : public llvm::PassInfoMixin<NativeEncode> {
  PassConfig Config;

public:
  explicit NativeEncode(PassConfig Config) : Config(std::move(Config)) {}

  llvm::PreservedAnalyses run(llvm::Module &Module,
                              llvm::ModuleAnalysisManager &Analysis) {
    bool Changed = false;
    RewriteStatistics Statistics;
    auto Name = Config.Definition.Name;
    auto Kind = Config.Definition.Kind;
    unsigned ScopeFilteredFunctions = 0;
    auto *Functions = Kind == Transform::Dispatch
        ? &Analysis.getResult<llvm::FunctionAnalysisManagerModuleProxy>(Module).getManager()
        : nullptr;
    for (llvm::Function &Function : Module) {
      if (Function.isDeclaration())
        continue;
      if (!Function.getName().starts_with(Config.Prefix)) {
        ++Statistics.PrefixFilteredFunctions;
        continue;
      }
      if (Config.Functions && !Config.Functions->contains(Function.getName())) {
        ++ScopeFilteredFunctions;
        continue;
      }
      RewriteStatistics Before = Statistics;
      if (Function.hasFnAttribute(llvm::Attribute::OptimizeNone)) {
        ++Statistics.OptimizeNoneFunctions;
      } else {
        switch (Kind) {
        case Transform::Compare:
          Changed |= rewriteComparisons(Function, Config.Rewrite, Statistics);
          break;
        case Transform::Dispatch:
          Changed |= rewriteDispatch(Function, *Functions, Config.Rewrite,
                                     Statistics);
          break;
        default:
          Changed |= rewriteIntegerOperations(Function, Config.Rewrite,
                                              Statistics);
        }
      }
      auto &Stream = llvm::errs();
      Stream << "native-obfuscator-function: pass=" << Name
             << " function=" << Function.getName() << " candidates="
             << Statistics.CandidateSites - Before.CandidateSites
             << " eligible="
             << Statistics.EligibleSites - Before.EligibleSites
             << " transformed="
             << Statistics.TransformedSites - Before.TransformedSites
             << " skipped_guard="
             << Statistics.GuardFilteredSites - Before.GuardFilteredSites
             << " skipped_cap="
             << Statistics.CapSkippedSites - Before.CapSkippedSites
             << " skipped_optnone="
             << Statistics.OptimizeNoneFunctions - Before.OptimizeNoneFunctions
             << " skipped_unsafe_control_flow="
             << Statistics.UnsafeControlFlowFunctions -
                    Before.UnsafeControlFlowFunctions;
      writeSiteRejections(Stream, Kind, Statistics, Before);
      Stream << '\n';
    }
    auto &Stream = llvm::errs();
    Stream << "native-obfuscator: " << Name
           << " candidates=" << Statistics.CandidateSites
           << " eligible=" << Statistics.EligibleSites
           << " transformed=" << Statistics.TransformedSites
           << " skipped_guard=" << Statistics.GuardFilteredSites
           << " skipped_cap=" << Statistics.CapSkippedSites
           << " skipped_prefix_functions=" << Statistics.PrefixFilteredFunctions
           << " skipped_scope_functions=" << ScopeFilteredFunctions
           << " skipped_optnone_functions=" << Statistics.OptimizeNoneFunctions
           << " skipped_unsafe_control_flow_functions="
           << Statistics.UnsafeControlFlowFunctions
           << " changed_functions=" << Statistics.ChangedFunctions
           << " max_sites_per_function=" << Config.Rewrite.MaxSites
           << " seed=" << Config.Rewrite.Seed;
    writeSiteRejections(Stream, Kind, Statistics);
    Stream << '\n';
    return Changed ? llvm::PreservedAnalyses::none()
                   : llvm::PreservedAnalyses::all();
  }
};

void registerPass(llvm::PassBuilder &Builder) {
  Builder.registerPipelineParsingCallback(
      [](llvm::StringRef Name, llvm::ModulePassManager &Manager,
         llvm::ArrayRef<llvm::PassBuilder::PipelineElement> InnerPipeline) {
        llvm::StringRef Pass = Name.split('<').first;
        for (const auto &Definition : Passes) {
          if (Pass != Definition.Name)
            continue;
          PassConfig Config{
              Definition,
              {Definition.Kind, 0, Definition.DefaultMaxSites},
              {},
              std::nullopt};
          if (!InnerPipeline.empty() || !readOptions(Name, Config)) {
            llvm::errs() << "invalid " << Pass << " options; expected " << Pass
                         << "<seed=UINT64;max=UINT;prefix=NAME"
                            ";functions=NAME+NAME or functions-file=BASENAME>\n";
            return false;
          }
          Manager.addPass(NativeEncode(std::move(Config)));
          return true;
        }
        return false;
      });
}

} // namespace

extern "C" LLVM_ATTRIBUTE_WEAK llvm::PassPluginLibraryInfo
llvmGetPassPluginInfo() {
  return {LLVM_PLUGIN_API_VERSION, "NativeEncode", "0.1.0", registerPass};
}
