"use client";

import React, { useState } from "react";
import { ChevronDown, Plus, TrendingUp, Users, Calendar, CloudLightning, ActivitySquare } from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import { FilterChip } from "./TrendBuilder";

type FilterSidebarProps = {
  onAddFilter: (filter: FilterChip) => void;
  activeFilters: FilterChip[];
};

type MenuSection = {
  id: string;
  title: string;
  icon: React.ReactNode;
  color: string;
  items: any[];
};

export default function FilterSidebar({ onAddFilter, activeFilters }: FilterSidebarProps) {
  const [openSections, setOpenSections] = useState<string[]>(["line_info", "public_data"]);

  const toggleSection = (id: string) => {
    setOpenSections((prev) => 
      prev.includes(id) ? prev.filter((s) => s !== id) : [...prev, id]
    );
  };

  const SECTIONS: MenuSection[] = [
    {
      id: "line_info",
      title: "Line Info",
      icon: <TrendingUp className="w-4 h-4" />,
      color: "from-[#00f0ff] to-[#0088ff]",
      items: [
        { id: "odds_spread_min", label: "Min Spread", type: "number", placeholder: "e.g. -3.5" },
        { id: "odds_spread_max", label: "Max Spread", type: "number", placeholder: "e.g. 7.0" },
        { id: "odds_movement", label: "Line Movement", type: "select", options: ["Toward Home", "Toward Away"] },
      ]
    },
    {
      id: "public_data",
      title: "Public Data",
      icon: <Users className="w-4 h-4" />,
      color: "from-[#7000ff] to-[#bd00ff]",
      items: [
        { id: "pub_ticket_max", label: "Max Public Ticket %", type: "range", min: 0, max: 100, default: 40 },
        { id: "pub_money_min", label: "Min Sharp Money %", type: "range", min: 0, max: 100, default: 60 },
        { id: "pub_sharp_side", label: "Sharp Side", type: "select", options: ["Home", "Away", "Over", "Under"] },
      ]
    },
    {
      id: "schedule",
      title: "Schedule",
      icon: <Calendar className="w-4 h-4" />,
      color: "from-[#ff0055] to-[#ff5500]",
      items: [
        { id: "sched_rest_min", label: "Min Rest Advantage", type: "number", placeholder: "Days" },
        { id: "sched_short_week", label: "Short Week", type: "boolean" },
      ]
    },
    {
      id: "weather",
      title: "Weather",
      icon: <CloudLightning className="w-4 h-4" />,
      color: "from-[#00ffaa] to-[#00aaee]",
      items: [
        { id: "wx_wind_min", label: "Min Wind Speed (mph)", type: "range", min: 0, max: 40, default: 15 },
        { id: "wx_temp_max", label: "Max Temp (°F)", type: "range", min: -20, max: 120, default: 35 },
        { id: "wx_exclude_dome", label: "Exclude Domes", type: "boolean" },
      ]
    },
    {
      id: "streaks",
      title: "Streaks",
      icon: <ActivitySquare className="w-4 h-4" />,
      color: "from-[#ffaa00] to-[#ff00aa]",
      items: [
        { id: "str_ats_win", label: "Min ATS Win Streak", type: "number", placeholder: "e.g. 3" },
        { id: "str_su_loss", label: "Min SU Loss Streak", type: "number", placeholder: "e.g. 2" },
      ]
    }
  ];

  return (
    <div className="w-80 glass-panel border-r border-white/5 h-full flex flex-col z-20">
      <div className="p-6 border-b border-white/5">
        <h2 className="text-xl font-display font-bold text-white flex items-center gap-2">
          <div className="w-2 h-6 rounded-full bg-gradient-to-b from-[#00f0ff] to-[#7000ff]" />
          Filters
        </h2>
        <p className="text-xs text-slate-400 mt-1">Select parameters to build query</p>
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {SECTIONS.map((section) => {
          const isOpen = openSections.includes(section.id);
          
          return (
            <div key={section.id} className="rounded-xl glass-panel-light overflow-hidden transition-all duration-300 border border-white/5 hover:border-white/10">
              <button
                onClick={() => toggleSection(section.id)}
                className="w-full flex items-center justify-between p-4 bg-white/5 hover:bg-white/10 transition-colors"
              >
                <div className="flex items-center gap-3">
                  <div className={`p-1.5 rounded-md bg-gradient-to-br ${section.color} bg-opacity-20 text-white`}>
                    {section.icon}
                  </div>
                  <span className="font-semibold text-sm text-slate-200">{section.title}</span>
                </div>
                <ChevronDown className={`w-4 h-4 text-slate-400 transition-transform duration-300 ${isOpen ? "rotate-180" : ""}`} />
              </button>
              
              <AnimatePresence>
                {isOpen && (
                  <motion.div
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: "auto", opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    className="overflow-hidden"
                  >
                    <div className="p-4 pt-2 space-y-4 border-t border-white/5">
                      {section.items.map((item) => (
                        <FilterItem 
                          key={item.id} 
                          item={item} 
                          category={section.title} 
                          onAdd={onAddFilter}
                          isActive={activeFilters.some(f => f.id === item.id)}
                        />
                      ))}
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// Subcomponent for individual filter inputs
function FilterItem({ item, category, onAdd, isActive }: { item: any, category: string, onAdd: any, isActive: boolean }) {
  const [val, setVal] = useState<any>(item.default || "");

  const handleApply = () => {
    if (val === "" && item.type !== "boolean") return;
    onAdd({
      id: item.id,
      category: category,
      label: item.type === 'boolean' ? item.label : `${item.label} ${val}`,
      value: item.type === 'boolean' ? true : val
    });
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <label className="text-xs font-medium text-slate-400">{item.label}</label>
        {isActive && <span className="w-1.5 h-1.5 rounded-full bg-[#00f0ff] shadow-[0_0_5px_#00f0ff]" />}
      </div>
      
      <div className="flex gap-2">
        {item.type === "number" && (
          <input 
            type="number" 
            placeholder={item.placeholder}
            value={val}
            onChange={(e) => setVal(e.target.value)}
            className="flex-1 bg-black/40 border border-white/10 rounded-lg px-3 py-1.5 text-sm text-white focus:outline-none focus:border-[#00f0ff]/50 transition-colors"
          />
        )}
        
        {item.type === "range" && (
          <div className="flex-1 flex items-center gap-3">
            <input 
              type="range" 
              min={item.min} 
              max={item.max} 
              value={val}
              onChange={(e) => setVal(e.target.value)}
              className="flex-1 accent-[#7000ff]"
            />
            <span className="text-xs font-mono text-slate-300 w-8">{val}</span>
          </div>
        )}
        
        {item.type === "select" && (
          <select 
            value={val}
            onChange={(e) => setVal(e.target.value)}
            className="flex-1 bg-black/40 border border-white/10 rounded-lg px-3 py-1.5 text-sm text-white focus:outline-none focus:border-[#00f0ff]/50 transition-colors appearance-none"
          >
            <option value="" disabled>Select...</option>
            {item.options.map((opt: string) => (
              <option key={opt} value={opt}>{opt}</option>
            ))}
          </select>
        )}

        {item.type === "boolean" ? (
          <button
            onClick={handleApply}
            className={`flex-1 py-1.5 rounded-lg text-xs font-bold transition-all border ${
              isActive 
                ? "bg-[#00f0ff]/20 text-[#00f0ff] border-[#00f0ff]/50" 
                : "bg-white/5 text-slate-300 border-white/10 hover:bg-white/10"
            }`}
          >
            {isActive ? "APPLIED" : "APPLY"}
          </button>
        ) : (
          <button 
            onClick={handleApply}
            className="p-1.5 rounded-lg bg-[#7000ff]/20 text-[#00f0ff] hover:bg-[#7000ff]/40 border border-[#7000ff]/30 transition-colors flex-shrink-0"
          >
            <Plus className="w-4 h-4" />
          </button>
        )}
      </div>
    </div>
  );
}
