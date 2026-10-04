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

    // Simulated Response
    setResults({
      roi: 12.4,
      winRate: 56.8,
      netProfit: 14.2,
      totalMatches: 843,
      wins: 479,
      losses: 364
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
