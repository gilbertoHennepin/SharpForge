"use client";

import React, { useState } from "react";
import { Play, Activity, Database, Settings2, X, Bookmark, BellRing } from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import { DndContext, closestCenter, DragEndEvent } from '@dnd-kit/core';
import { SortableContext, horizontalListSortingStrategy, arrayMove, useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import FilterSidebar from "./FilterSidebar";
import ResultsWidget from "./ResultsWidget";

export type FilterChip = {
  id: string;
  category: string;
  label: string;
  value: any;
};

// Sortable Chip Component
function SortableFilterChip({ filter, onRemove }: { filter: FilterChip, onRemove: (id: string) => void }) {
  const { attributes, listeners, setNodeRef, transform, transition } = useSortable({ id: filter.id });
  
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      {...listeners}
      className="flex items-center gap-2 px-4 py-2 rounded-full glass-panel-light border border-[#00f0ff]/30 text-sm font-medium hover:border-[#00f0ff]/80 transition-colors cursor-grab active:cursor-grabbing z-10 bg-[#0f172a]"
    >
      <span className="text-slate-400 text-xs uppercase tracking-wider">{filter.category}:</span>
      <span className="text-white">{filter.label}</span>
      <button 
        onClick={(e) => { e.stopPropagation(); onRemove(filter.id); }}
        className="ml-1 p-0.5 rounded-full hover:bg-white/10 text-slate-400 hover:text-white transition-colors"
      >
        <X className="w-3.5 h-3.5" />
      </button>
    </div>
  );
}

export default function TrendBuilder({ initialFilters = [], onFiltersChange }: { initialFilters?: FilterChip[], onFiltersChange?: (filters: FilterChip[]) => void }) {
  const [filters, setFilters] = useState<FilterChip[]>(initialFilters);
  const [isRunning, setIsRunning] = useState(false);
  const [results, setResults] = useState<any>(null);
  const [isSaved, setIsSaved] = useState(false);

  // Sync with parent when filters change internally
  const updateFilters = (newFilters: FilterChip[]) => {
    setFilters(newFilters);
    if (onFiltersChange) onFiltersChange(newFilters);
    setIsSaved(false);
  };

  const handleAddFilter = (filter: FilterChip) => {
    updateFilters([...filters.filter((f) => f.id !== filter.id), filter]);
  };

  const handleRemoveFilter = (id: string) => {
    updateFilters(filters.filter((f) => f.id !== id));
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (over && active.id !== over.id) {
      const oldIndex = filters.findIndex(i => i.id === active.id);
      const newIndex = filters.findIndex(i => i.id === over.id);
      updateFilters(arrayMove(filters, oldIndex, newIndex));
    }
  };

  const handleRunBacktest = async () => {
    if (filters.length === 0) return;
    setIsRunning(true);
    setResults(null);
    setIsSaved(false);
    
    await new Promise((resolve) => setTimeout(resolve, 2000));
    
    // Generate dynamic fake results to simulate complex queries
    const isOverfitted = filters.length >= 3;
    const baseWin = 52.0 + (filters.length * 2.1) + (Math.random() * 5);
    const baseRoi = (baseWin - 52.38) * 1.5;
    const matchCount = Math.floor(Math.max(10, 1000 - (filters.length * 300) + (Math.random() * 100)));
    const wins = Math.floor(matchCount * (baseWin / 100));
    
    setResults({
      roi: parseFloat(baseRoi.toFixed(2)),
      winRate: parseFloat(baseWin.toFixed(2)),
      netProfit: parseFloat(((wins) - ((matchCount - wins) * 1.1)).toFixed(2)),
      totalMatches: matchCount,
      wins: wins,
      losses: matchCount - wins,
      pValue: isOverfitted ? (Math.random() * 0.3) : (0.001 + Math.random() * 0.04),
      confidenceLevel: isOverfitted && matchCount < 50 ? "LOW" : (matchCount > 200 ? "HIGH" : "MEDIUM"),
      activeGamesTonight: Math.floor(Math.random() * 4)
    });
    
    setIsRunning(false);
  };

  const handleSaveTrend = () => {
    setIsSaved(true);
  };

  return (
    <div className="flex h-screen overflow-hidden bg-[#020617]">
      <FilterSidebar onAddFilter={handleAddFilter} activeFilters={filters} />

      <div className="flex-1 flex flex-col relative overflow-hidden">
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
          
          <div className="flex items-center gap-4">
            {results && !isRunning && (
              <button
                onClick={handleSaveTrend}
                className={`flex items-center gap-2 px-4 py-3 rounded-xl font-bold transition-all border ${
                  isSaved 
                    ? "bg-green-500/20 text-green-400 border-green-500/50" 
                    : "glass-panel-light text-white hover:bg-white/10"
                }`}
              >
                {isSaved ? <BellRing className="w-5 h-5" /> : <Bookmark className="w-5 h-5" />}
                {isSaved ? "ALERTS ACTIVE" : "SAVE & TRACK"}
              </button>
            )}
            <button
              onClick={handleRunBacktest}
              disabled={isRunning || filters.length === 0}
              className={`glow-btn px-6 py-3 rounded-xl font-bold flex items-center gap-2 transition-all ${
                filters.length > 0 
                  ? "bg-white text-slate-900 shadow-[0_0_20px_rgba(0,240,255,0.4)] hover:scale-105" 
                  : "bg-slate-800 text-slate-500 cursor-not-allowed"
              }`}
            >
              {isRunning ? <Activity className="w-5 h-5 animate-pulse text-[#7000ff]" /> : <Play className="w-5 h-5" fill="currentColor" />}
              {isRunning ? "PROCESSING..." : "RUN BACKTEST"}
            </button>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto p-8 flex flex-col gap-8 relative z-0">
          {/* TrendyBot AI Input */}
          <div className="glass-panel p-2 rounded-2xl flex items-center gap-3 relative shadow-[0_0_20px_rgba(112,0,255,0.15)] border-[#7000ff]/30 focus-within:border-[#00f0ff]/50 transition-all">
            <div className="p-3 bg-gradient-to-br from-[#7000ff] to-[#bd00ff] rounded-xl flex-shrink-0">
              <Database className="w-5 h-5 text-white" />
            </div>
            <input 
              type="text"
              placeholder="Ask TrendyBot: e.g., 'Show me NFL trends for road favorites on a 5+ game losing streak'"
              className="flex-1 bg-transparent border-none text-white text-lg placeholder-slate-500 focus:outline-none focus:ring-0 font-medium"
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  const val = e.currentTarget.value.toLowerCase();
                  let newFilters = [...filters];
                  
                  if (val.includes('nfl')) newFilters.push({ id: 'league', category: 'League', label: 'NFL', value: 1 });
                  if (val.includes('nba')) newFilters.push({ id: 'league', category: 'League', label: 'NBA', value: 3 });
                  if (val.includes('road favorite') || val.includes('away fav')) {
                    newFilters.push({ id: 'bet_target', category: 'Target', label: 'Away Spread', value: 'AWAY_SPREAD' });
                    newFilters.push({ id: 'odds_spread_min', category: 'Min Spread', label: 'Home Dog (+0.5)', value: 0.5 });
                  }
                  if (val.includes('home underdog') || val.includes('home dog')) {
                    newFilters.push({ id: 'bet_target', category: 'Target', label: 'Home Spread', value: 'HOME_SPREAD' });
                    newFilters.push({ id: 'odds_spread_min', category: 'Min Spread', label: 'Home Dog (+0.5)', value: 0.5 });
                  }
                  if (val.includes('losing streak')) newFilters.push({ id: 'str_su_loss', category: 'Min SU Loss Streak', label: '3+ Games', value: 3 });
                  if (val.includes('wind') || val.includes('windy')) newFilters.push({ id: 'wx_high_wind', category: 'Smart Weather', label: 'High Wind (>15mph)', value: true });
                  
                  updateFilters(newFilters);
                  e.currentTarget.value = '';
                  setTimeout(handleRunBacktest, 500);
                }
              }}
            />
          </div>

          {/* Draggable Staging Area */}
          <div className="glass-panel p-6 rounded-2xl flex flex-col gap-4 min-h-[160px]">
            <div className="flex items-center gap-2 text-slate-300">
              <Settings2 className="w-5 h-5" />
              <h2 className="text-lg font-semibold font-display">Active Filters (Drag to Reorder)</h2>
            </div>
            
            <div className="flex flex-wrap gap-3">
              <DndContext collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
                <SortableContext items={filters.map(f => f.id)} strategy={horizontalListSortingStrategy}>
                  {filters.length === 0 && (
                    <div className="text-slate-500 text-sm italic py-2">Drag filters from the sidebar or click to add them here.</div>
                  )}
                  {filters.map((filter) => (
                    <SortableFilterChip key={filter.id} filter={filter} onRemove={handleRemoveFilter} />
                  ))}
                </SortableContext>
              </DndContext>
            </div>
          </div>

          <ResultsWidget isRunning={isRunning} results={results} />
        </main>
      </div>
    </div>
  );
}
