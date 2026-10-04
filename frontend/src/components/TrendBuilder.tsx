"use client";

import React, { useState } from "react";
import { Play, Activity, Database, Settings2, X, Plus } from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import FilterSidebar from "./FilterSidebar";
import ResultsWidget from "./ResultsWidget";

export type FilterChip = {
  id: string;
  category: string;
  label: string;
  value: any;
};

export default function TrendBuilder() {
  const [filters, setFilters] = useState<FilterChip[]>([]);
  const [isRunning, setIsRunning] = useState(false);
  const [results, setResults] = useState<any>(null);

  const handleAddFilter = (filter: FilterChip) => {
    // Replace if exists in same category with same id
    setFilters((prev) => {
      const filtered = prev.filter((f) => f.id !== filter.id);
      return [...filtered, filter];
    });
  };

  const handleRemoveFilter = (id: string) => {
    setFilters((prev) => prev.filter((f) => f.id !== id));
  };

  const handleRunBacktest = async () => {
    if (filters.length === 0) return;
    setIsRunning(true);
    setResults(null);
    
    // Simulate API delay for dramatic effect
    await new Promise((resolve) => setTimeout(resolve, 2000));
    
    // Create serialized JSON to log to console
    const payload = filters.reduce((acc, curr) => {
      acc[curr.id] = curr.value;
      return acc;
    }, {} as any);
    
    console.log("Serialized Backtest Payload:", JSON.stringify(payload, null, 2));

    // Simulated Response Logic based on number of filters to demo Sharp Trap
    const isOverfitted = filters.length >= 3;
    
    setResults({
      roi: isOverfitted ? 28.5 : 12.4,
      winRate: isOverfitted ? 80.0 : 56.8,
      netProfit: isOverfitted ? 4.2 : 14.2,
      totalMatches: isOverfitted ? 12 : 843,
      wins: isOverfitted ? 10 : 479,
      losses: isOverfitted ? 2 : 364,
      pValue: isOverfitted ? 0.22 : 0.003,
      confidenceLevel: isOverfitted ? "LOW" : "HIGH"
    });
    
    setIsRunning(false);
  };

  return (
    <div className="flex h-screen overflow-hidden bg-[#020617]">
      {/* Sidebar */}
      <FilterSidebar onAddFilter={handleAddFilter} activeFilters={filters} />

      {/* Main Content */}
      <div className="flex-1 flex flex-col relative overflow-hidden">
        
        {/* Navbar */}
        <header className="h-20 glass-panel-light border-b border-white/5 flex items-center justify-between px-8 z-10">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-gradient-to-br from-[#00f0ff]/20 to-[#7000ff]/20 border border-[#00f0ff]/30">
              <Database className="w-6 h-6 text-[#00f0ff]" />
            </div>
            <div>
              <h1 className="text-2xl font-display font-bold tracking-tight text-white">Trend Builder</h1>
              <p className="text-sm text-slate-400 font-medium">Deep Situational Backtesting Engine</p>
            </div>
          </div>
          
          <button
            onClick={handleRunBacktest}
            disabled={isRunning || filters.length === 0}
            className={`glow-btn px-6 py-3 rounded-xl font-bold flex items-center gap-2 transition-all ${
              filters.length > 0 
                ? "bg-white text-slate-900 shadow-[0_0_20px_rgba(0,240,255,0.4)] hover:scale-105" 
                : "bg-slate-800 text-slate-500 cursor-not-allowed"
            }`}
          >
            {isRunning ? (
              <Activity className="w-5 h-5 animate-pulse text-[#7000ff]" />
            ) : (
              <Play className="w-5 h-5" fill="currentColor" />
            )}
            {isRunning ? "PROCESSING..." : "RUN BACKTEST"}
          </button>
        </header>

        {/* Workspace */}
        <main className="flex-1 overflow-y-auto p-8 flex flex-col gap-8 relative z-0">
          
          {/* TrendyBot AI Input */}
          <div className="glass-panel p-2 rounded-2xl flex items-center gap-3 relative shadow-[0_0_20px_rgba(112,0,255,0.15)] border-[#7000ff]/30 focus-within:border-[#00f0ff]/50 focus-within:shadow-[0_0_30px_rgba(0,240,255,0.2)] transition-all">
            <div className="p-3 bg-gradient-to-br from-[#7000ff] to-[#bd00ff] rounded-xl flex-shrink-0">
              <Database className="w-5 h-5 text-white" />
            </div>
            <input 
              type="text"
              placeholder="Ask TrendyBot: e.g., 'Show me NFL trends for road favorites on a 5+ game losing streak'"
              className="flex-1 bg-transparent border-none text-white text-lg placeholder-slate-500 focus:outline-none focus:ring-0 font-medium"
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  const val = e.currentTarget.value;
                  if (val.toLowerCase().includes('nfl')) {
                    handleAddFilter({ id: 'league', category: 'League', label: 'NFL', value: 1 });
                  }
                  if (val.toLowerCase().includes('road favorite') || val.toLowerCase().includes('away fav')) {
                    handleAddFilter({ id: 'bet_target', category: 'Target', label: 'Away Spread', value: 'AWAY_SPREAD' });
                    handleAddFilter({ id: 'odds_spread_min', category: 'Min Spread', label: 'Home Dog (+0.5)', value: 0.5 });
                  }
                  if (val.toLowerCase().includes('losing streak')) {
                    handleAddFilter({ id: 'str_su_loss', category: 'Min SU Loss Streak', label: '5 Games', value: 5 });
                  }
                  e.currentTarget.value = '';
                  setTimeout(handleRunBacktest, 500); // auto run
                }
              }}
            />
            <button className="px-5 py-3 rounded-xl bg-white/5 hover:bg-white/10 text-slate-300 font-bold text-sm transition-colors uppercase tracking-wider">
              Parse
            </button>
          </div>

          {/* Staging Area */}
          <div className="glass-panel p-6 rounded-2xl flex flex-col gap-4 min-h-[160px]">
            <div className="flex items-center gap-2 text-slate-300">
              <Settings2 className="w-5 h-5" />
              <h2 className="text-lg font-semibold font-display">Active Filters</h2>
              <span className="ml-auto text-xs font-bold px-2 py-1 rounded-full bg-slate-800 text-slate-400 border border-slate-700">
                {filters.length} APPLIED
              </span>
            </div>
            
            <div className="flex flex-wrap gap-3">
              <AnimatePresence>
                {filters.length === 0 && (
                  <motion.div 
                    initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
                    className="text-slate-500 text-sm flex items-center gap-2 h-10 italic"
                  >
                    Select parameters from the sidebar to build your strategy.
                  </motion.div>
                )}
                {filters.map((filter) => (
                  <motion.div
                    key={filter.id}
                    initial={{ scale: 0.8, opacity: 0, y: 10 }}
                    animate={{ scale: 1, opacity: 1, y: 0 }}
                    exit={{ scale: 0.8, opacity: 0, y: -10 }}
                    className="flex items-center gap-2 px-4 py-2 rounded-full glass-panel-light border border-[#00f0ff]/30 text-sm font-medium hover:border-[#00f0ff]/80 transition-colors group"
                  >
                    <span className="text-slate-400 text-xs uppercase tracking-wider">{filter.category}:</span>
                    <span className="text-white">{filter.label}</span>
                    <button 
                      onClick={() => handleRemoveFilter(filter.id)}
                      className="ml-1 p-0.5 rounded-full hover:bg-white/10 text-slate-400 hover:text-white transition-colors"
                    >
                      <X className="w-3.5 h-3.5" />
                    </button>
                  </motion.div>
                ))}
              </AnimatePresence>
            </div>
          </div>

          {/* Results Widget */}
          <ResultsWidget isRunning={isRunning} results={results} />
          
        </main>
      </div>
    </div>
  );
}
