"use client";

import React from "react";
import { CheckCircle, TrendingUp, Users, Calendar, Activity, Database } from "lucide-react";
import { motion } from "framer-motion";
import { FilterChip } from "./TrendBuilder";

export default function DiscoverFeed({ onLoadSystem }: { onLoadSystem?: (filters: FilterChip[]) => void }) {
  const verifiedSystems = [
    {
      id: 1,
      title: "NFL Road Underdogs off a Bye",
      description: "Fading the public on road dogs who have a rest advantage of 7+ days.",
      sport: "NFL",
      winRate: 68.4,
      roi: 32.1,
      profit: "+45.2u",
      matches: 124,
      activeGames: 1,
      filters: ["Away Spread", "Rest > 7", "Public < 40%"],
      filterObjects: [
        { id: "league_id", category: "League", label: "NFL", value: 1 },
        { id: "bet_target", category: "Target", label: "Away Spread", value: "AWAY_SPREAD" },
        { id: "odds_spread_min", category: "Min Spread", label: "Home Dog (+0.5)", value: 0.5 },
        { id: "sched_rest_min", category: "Min Rest Advantage", label: "7 Days", value: 7 },
        { id: "pub_ticket_max", category: "Max Public Ticket %", label: "40%", value: 40 }
      ]
    },
    {
      id: 2,
      title: "NBA Home Favorites off B2B Loss",
      description: "Targeting home favorites coming off a back-to-back losing streak.",
      sport: "NBA",
      winRate: 62.1,
      roi: 24.5,
      profit: "+31.8u",
      matches: 312,
      activeGames: 0,
      filters: ["Home Spread", "SU Loss Streak >= 2"],
      filterObjects: [
        { id: "league_id", category: "League", label: "NBA", value: 3 },
        { id: "bet_target", category: "Target", label: "Home Spread", value: "HOME_SPREAD" },
        { id: "odds_spread_max", category: "Max Spread", label: "Home Fav (-0.5)", value: -0.5 },
        { id: "str_su_loss", category: "Min SU Loss Streak", label: "2 Games", value: 2 }
      ]
    },
    {
      id: 3,
      title: "MLB Windy Unders",
      description: "Taking the under in open-air stadiums when wind is blowing in at 15+ mph.",
      sport: "MLB",
      winRate: 59.8,
      roi: 18.2,
      profit: "+22.5u",
      matches: 485,
      activeGames: 3,
      filters: ["Under", "Wind >= 15mph", "Exclude Domes"],
      filterObjects: [
        { id: "league_id", category: "League", label: "MLB", value: 4 },
        { id: "bet_target", category: "Target", label: "Under", value: "UNDER" },
        { id: "wx_high_wind", category: "Smart Weather", label: "High Wind (>15mph)", value: true },
        { id: "wx_exclude_dome", category: "Smart Weather", label: "Exclude Domes", value: true }
      ]
    }
  ];

  return (
    <div className="flex h-screen overflow-hidden bg-[#020617] w-full">
      <div className="flex-1 flex flex-col relative overflow-hidden">
        
        <header className="h-20 glass-panel-light border-b border-white/5 flex items-center px-8 z-10 gap-3">
          <div className="p-2 rounded-lg bg-gradient-to-br from-[#00ffaa]/20 to-[#00f0ff]/20 border border-[#00ffaa]/30">
            <CheckCircle className="w-6 h-6 text-[#00ffaa]" />
          </div>
          <div>
            <h1 className="text-2xl font-display font-bold tracking-tight text-white">Verified Systems</h1>
            <p className="text-sm text-slate-400 font-medium">Hand-picked, highly profitable trends vetted by professionals</p>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto p-8 relative z-0">
          <div className="max-w-5xl mx-auto space-y-6">
            
            {verifiedSystems.map((sys, idx) => (
              <motion.div 
                key={sys.id}
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: idx * 0.1 }}
                className="glass-panel p-6 rounded-2xl border border-white/5 hover:border-[#00f0ff]/30 transition-all group"
              >
                <div className="flex justify-between items-start">
                  <div>
                    <div className="flex items-center gap-3 mb-2">
                      <span className="px-2.5 py-1 rounded-md text-xs font-bold bg-[#7000ff]/20 text-[#00f0ff] uppercase tracking-wider">
                        {sys.sport}
                      </span>
                      {sys.activeGames > 0 && (
                        <span className="px-2.5 py-1 rounded-md text-xs font-bold bg-[#00ffaa]/20 text-[#00ffaa] flex items-center gap-1">
                          <span className="relative flex h-2 w-2">
                            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-[#00ffaa] opacity-75"></span>
                            <span className="relative inline-flex rounded-full h-2 w-2 bg-[#00ffaa]"></span>
                          </span>
                          {sys.activeGames} ACTIVE TONIGHT
                        </span>
                      )}
                    </div>
                    <h2 className="text-xl font-display font-bold text-white mb-1 group-hover:text-[#00f0ff] transition-colors">
                      {sys.title}
                    </h2>
                    <p className="text-slate-400 text-sm max-w-2xl">{sys.description}</p>
                    
                    <div className="flex gap-2 mt-4">
                      {sys.filters.map(f => (
                        <span key={f} className="px-3 py-1 rounded-full text-xs font-medium bg-white/5 text-slate-300 border border-white/10">
                          {f}
                        </span>
                      ))}
                    </div>
                  </div>

                  <div className="flex gap-6 text-right">
                    <div>
                      <div className="text-slate-500 text-xs font-medium uppercase tracking-wider mb-1">Win Rate</div>
                      <div className="text-2xl font-bold text-[#ff0055]">{sys.winRate}%</div>
                      <div className="text-slate-400 text-xs mt-1">{sys.matches} Matches</div>
                    </div>
                    <div>
                      <div className="text-slate-500 text-xs font-medium uppercase tracking-wider mb-1">Net Profit</div>
                      <div className="text-2xl font-bold text-[#00ffaa]">{sys.profit}</div>
                      <div className="text-[#00f0ff] text-xs mt-1 font-bold">ROI: {sys.roi}%</div>
                    </div>
                  </div>
                </div>

                <div className="mt-6 pt-4 border-t border-white/5 flex justify-end gap-3 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button className="px-4 py-2 rounded-lg bg-white/5 hover:bg-white/10 text-slate-300 font-bold text-sm transition-colors">
                    View Logs
                  </button>
                  <button 
                    onClick={() => onLoadSystem && onLoadSystem(sys.filterObjects)}
                    className="px-4 py-2 rounded-lg bg-gradient-to-r from-[#7000ff] to-[#00f0ff] text-white font-bold text-sm hover:shadow-[0_0_20px_rgba(0,240,255,0.4)] transition-all"
                  >
                    Load into Builder
                  </button>
                </div>
              </motion.div>
            ))}

          </div>
        </main>
      </div>
    </div>
  );
}
