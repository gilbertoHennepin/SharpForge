"use client";

import React, { useState } from "react";
import { Database, CheckCircle } from "lucide-react";
import TrendBuilder from "@/components/TrendBuilder";
import DiscoverFeed from "@/components/DiscoverFeed";

export default function Home() {
  const [activeTab, setActiveTab] = useState<"builder" | "discover">("builder");

  return (
    <div className="flex h-screen w-full bg-[#020617] text-slate-300">
      
      {/* Global App Sidebar */}
      <nav className="w-20 glass-panel border-r border-white/5 flex flex-col items-center py-6 z-50 shrink-0">
        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-[#00f0ff] to-[#7000ff] flex items-center justify-center mb-12 shadow-[0_0_20px_rgba(0,240,255,0.3)]">
          <Database className="w-6 h-6 text-white" />
        </div>

        <div className="flex flex-col gap-6">
          <button 
            onClick={() => setActiveTab("builder")}
            className={`p-3 rounded-xl transition-all ${
              activeTab === "builder" 
                ? "bg-[#7000ff]/20 text-[#00f0ff] shadow-[0_0_15px_rgba(112,0,255,0.2)]" 
                : "text-slate-500 hover:text-slate-300 hover:bg-white/5"
            }`}
            title="Trend Builder"
          >
            <Database className="w-6 h-6" />
          </button>
          
          <button 
            onClick={() => setActiveTab("discover")}
            className={`p-3 rounded-xl transition-all ${
              activeTab === "discover" 
                ? "bg-[#00ffaa]/20 text-[#00ffaa] shadow-[0_0_15px_rgba(0,255,170,0.2)]" 
                : "text-slate-500 hover:text-slate-300 hover:bg-white/5"
            }`}
            title="Verified Systems"
          >
            <CheckCircle className="w-6 h-6" />
          </button>
        </div>
      </nav>

      {/* Main Content Area */}
      <main className="flex-1 overflow-hidden relative">
        {activeTab === "builder" ? <TrendBuilder /> : <DiscoverFeed />}
      </main>

    </div>
  );
}
